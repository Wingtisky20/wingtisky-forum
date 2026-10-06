package com.wingtisky.forum.forum.user.ratelimit;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.infra.redis.SlidingWindowRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitInterceptor} 的**规则匹配**部分。
 *
 * <p>M1 时这里没什么可测的（规则只按路径匹配），M2 加了方法维度之后有了：
 * 同一条 `/api/posts` 上的读与写要用两套阈值，配错的表现是
 * **要么公开的列表被发帖的严格阈值误伤，要么发帖等于没限**——
 * 两种都不报错，只在上线后被人发现。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private SlidingWindowRateLimiter redisLimiter;

    @Mock
    private LocalRateLimiter localLimiter;

    private RateLimitProperties properties;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        interceptor = new RateLimitInterceptor(properties, redisLimiter, localLimiter);
    }

    private static RateLimitProperties.Rule rule(String path, int limit, HttpMethod... methods) {
        RateLimitProperties.Rule r = new RateLimitProperties.Rule();
        r.setPath(path);
        r.setMethods(methods.length == 0 ? null : List.of(methods));
        r.setDimension(RateLimitProperties.Dimension.USER);
        r.setLimit(limit);
        r.setWindow(Duration.ofMinutes(1));
        r.setFallback(RateLimitProperties.Fallback.OPEN);
        return r;
    }

    /** 按 M2 的真实配置排一份规则：先具体、后通配。 */
    private void useRealisticRules() {
        properties.setRules(List.of(
                rule("/api/posts", 10, HttpMethod.POST),
                rule("/api/**", 30, HttpMethod.POST, HttpMethod.PUT,
                        HttpMethod.PATCH, HttpMethod.DELETE),
                rule("/api/**", 100, HttpMethod.GET)));
    }

    private boolean handle(String method, String uri) {
        return interceptor.preHandle(
                new MockHttpServletRequest(method, uri), new MockHttpServletResponse(), new Object());
    }

    private void allowAll() {
        when(redisLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(new SlidingWindowRateLimiter.RateLimitResult(true, 1, 100));
    }

    @Test
    @DisplayName("★ 同一条路径按方法命中不同规则：POST 发帖用 10、GET 列表用 100、DELETE 用 30")
    void matchesDifferentRulesByMethod() {
        // 这条同时钉住一个容易写错的地方：**"路径命中但方法不符"必须继续往后找**。
        // 若写成"先按路径找到第一条、再判方法、不符就放行"，
        // GET /api/posts 会撞上 POST 那条然后径直放行——
        // 表现是这个接口完全不受限流，而配置看起来完全正确。

        useRealisticRules();
        allowAll();

        handle("POST", "/api/posts");
        handle("GET", "/api/posts");
        handle("DELETE", "/api/posts/1");

        verify(redisLimiter).tryAcquire(anyString(), eq(10), anyLong());
        verify(redisLimiter).tryAcquire(anyString(), eq(100), anyLong());
        verify(redisLimiter).tryAcquire(anyString(), eq(30), anyLong());
    }

    @Test
    @DisplayName("★ Key 里带上方法——否则「读了 100 次 + 想发帖」会被误判超限")
    void keyIncludesMethod() {
        useRealisticRules();
        allowAll();

        handle("POST", "/api/posts");
        handle("GET", "/api/posts");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisLimiter, times(2)).tryAcquire(keys.capture(), anyInt(), anyLong());

        // 两条请求路径一样、方法不同 → 必须是两个不同的计数器
        assertThat(keys.getAllValues())
                .anyMatch(k -> k.contains("POST:_api_posts"))
                .anyMatch(k -> k.contains("GET:_api_posts"));
    }

    @Test
    @DisplayName("没有规则覆盖这个请求方法时，直接放行、**不查限流器**")
    void passesThroughWhenNoRuleMatchesMethod() {
        // 只配了 GET，却来了个 DELETE —— 应当继续往后找、找不到就不限流，
        // 而不是拿 GET 那条规则硬套
        properties.setRules(List.of(rule("/api/**", 100, HttpMethod.GET)));

        assertThat(handle("DELETE", "/api/posts/1")).isTrue();
        verifyNoInteractions(redisLimiter);
    }

    @Test
    @DisplayName("不配方法 = 不限方法（老配置仍然能用）")
    void ruleWithoutMethodsMatchesAny() {
        properties.setRules(List.of(rule("/api/**", 100)));
        allowAll();

        assertThat(handle("DELETE", "/api/posts/1")).isTrue();

        verify(redisLimiter).tryAcquire(anyString(), eq(100), anyLong());
    }

    @Test
    @DisplayName("超限时抛 A0004（429）")
    void throwsWhenLimited() {
        properties.setRules(List.of(rule("/api/**", 100, HttpMethod.GET)));
        when(redisLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(new SlidingWindowRateLimiter.RateLimitResult(false, 100, 100));

        assertThatThrownBy(() -> handle("GET", "/api/posts"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    }
}
