package com.wingtisky.forum.forum.user.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.user.ratelimit.LocalRateLimiter;
import com.wingtisky.forum.forum.user.ratelimit.RateLimitProperties;
import com.wingtisky.forum.forum.user.security.JwtTokenProvider;
import com.wingtisky.forum.forum.user.security.RefreshTokenStore;
import com.wingtisky.forum.infra.redis.SlidingWindowRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService} 的**用户名维度限流**部分。
 *
 * <p>这是 M1 有意留的缺口（设计稿 §5.4），M2 补上。它挡的是"很多台机器试同一个账号"
 * ——与按 IP 限不重复。这里最要紧的一条是：**超限时不能被绕过到密码校验那一层**，
 * 否则撞库请求照样会打到 BCrypt（每次约 100ms），限流就没意义了。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private JwtTokenProvider tokenProvider;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private SlidingWindowRateLimiter redisLimiter;
    @Mock
    private LocalRateLimiter localLimiter;

    private final RateLimitProperties properties = new RateLimitProperties();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userService, tokenProvider, refreshTokenStore,
                properties, redisLimiter, localLimiter);
    }

    private void redisAllows() {
        when(redisLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(new SlidingWindowRateLimiter.RateLimitResult(true, 1, 5));
    }

    private static BizException err(Throwable e) {
        return (BizException) e;
    }

    @Test
    @DisplayName("★ 用户名维度超限 → 429，且**不会去验密码**")
    void blocksBeforePasswordCheck() {
        when(redisLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(new SlidingWindowRateLimiter.RateLimitResult(false, 5, 5));

        assertThatThrownBy(() -> authService.login("victim", "whatever"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(err(e).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        // 关键：撞库请求不该打到 BCrypt 比对（每次约 100ms）——
        // 限流放在密码校验之后的话，它来得太晚
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("★ 按用户名分桶：换个用户名不会被上一个的计数连累")
    void limitsPerUsername() {
        redisAllows();
        when(userService.authenticate(anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.LOGIN_FAILED));

        assertThatThrownBy(() -> authService.login("alice", "pw")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> authService.login("bob", "pw")).isInstanceOf(BizException.class);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisLimiter, times(2)).tryAcquire(keys.capture(), anyInt(), anyLong());

        // 两个用户名各自一个计数器——否则 Alice 输错几次会把 Bob 也关在门外
        assertThat(keys.getAllValues())
                .anyMatch(k -> k.contains(":alice:"))
                .anyMatch(k -> k.contains(":bob:"));
    }

    @Test
    @DisplayName("★ Redis 挂了 → 退回本地内存；本地也说超限 → 仍然 429（**不放行**）")
    void fallsBackToLocalAndStillBlocks() {
        when(redisLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));
        when(localLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> authService.login("victim", "pw"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(err(e).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        // 放行等于在最需要它的时候把门打开——防撞库是这一维度限流的唯一目的
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("Redis 挂了但本地内存还放行 → 继续走认证流程")
    void localFallbackLetsThroughWhenUnderLimit() {
        when(redisLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));
        when(localLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(userService.authenticate(anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.LOGIN_FAILED));

        // 已经走到认证了 —— 说明限流这一关放行了
        assertThatThrownBy(() -> authService.login("someone", "pw"))
                .satisfies(e -> assertThat(err(e).getErrorCode())
                        .isEqualTo(ErrorCode.LOGIN_FAILED));
    }

    @Test
    @DisplayName("用户名为空时跳过限流（避免拿空串当桶，把所有空用户名请求挤在一个计数器里）")
    void skipsWhenUsernameBlank() {
        when(userService.authenticate(anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.LOGIN_FAILED));

        assertThatThrownBy(() -> authService.login("   ", "pw")).isInstanceOf(BizException.class);

        verifyNoInteractions(redisLimiter, localLimiter);
    }
}
