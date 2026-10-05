package com.wingtisky.forum.forum.user.ratelimit;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.common.security.CurrentUser;
import com.wingtisky.forum.infra.redis.RedisKey;
import com.wingtisky.forum.infra.redis.SlidingWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 限流拦截器：把"这次请求该按什么规则限"与"怎么限"接起来。
 *
 * <p><b>为什么用 MVC 拦截器而不是 Servlet 过滤器</b>：拦截器在 Spring Security
 * 过滤器链**之后**执行，因此能拿到已经建立好的认证上下文——按用户维度限流需要它。
 * 放进 Security 的过滤器链里也可以，但那样要处理过滤器的顺序，收益为零。
 *
 * <p><b>M9 之后要前移</b>：architecture.md §4.1 写了理由——在网关挡掉无效流量
 * 比在每个服务里挡更省资源。现在不做，因为 M1–M8 只有一个进程。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final RateLimitProperties properties;
    private final SlidingWindowRateLimiter redisLimiter;
    private final LocalRateLimiter localLimiter;

    public RateLimitInterceptor(RateLimitProperties properties,
                                SlidingWindowRateLimiter redisLimiter,
                                LocalRateLimiter localLimiter) {
        this.properties = properties;
        this.redisLimiter = redisLimiter;
        this.localLimiter = localLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled()) {
            return true;
        }
        RateLimitProperties.Rule rule = matchRule(request.getRequestURI());
        if (rule == null) {
            return true;
        }

        String key = RedisKey.rateLimit(
                rule.getDimension().name().toLowerCase(),
                resolveDimensionValue(rule, request),
                request.getRequestURI());

        boolean allowed;
        try {
            allowed = redisLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow().toMillis()).allowed();
        } catch (RuntimeException e) {
            // ⚠️ 只包住 Redis 调用本身。若把下面的 BizException 也包进来，
            // "超限被拒"会被误判成"Redis 故障"而走降级分支——限流器会自己把自己放行。
            return handleFallback(rule, key, e);
        }

        if (!allowed) {
            log.warn("触发限流: path={}, dimension={}, key={}, limit={}/{}",
                    request.getRequestURI(), rule.getDimension(), key,
                    rule.getLimit(), rule.getWindow());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return true;
    }

    /**
     * Redis 不可用时的降级。**策略来自配置，不是写死的判断。**
     */
    private boolean handleFallback(RateLimitProperties.Rule rule, String key, RuntimeException cause) {
        // 记 ERROR 而不是 WARN：限流正在失效，这是需要被看到的信号
        log.error("限流降级：Redis 不可用，path={}, 策略={}", rule.getPath(), rule.getFallback(), cause);

        switch (rule.getFallback()) {
            case OPEN:
                // 放行。理由见设计 §5.3：限流是防滥用，不是正确性防线
                // （超卖靠 Lua 原子扣减、重复下单靠幂等），而全站不可用的代价更大
                return true;
            case LOCAL:
                // 退回单机内存。防撞库这类"限流本身就是安全目的"的接口用它
                if (!localLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow().toMillis())) {
                    throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
                }
                return true;
            case CLOSED:
                // 拒绝一切。**慎用**——Redis 抖一下就是全站不可用
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
            default:
                return true;
        }
    }

    /** 按配置顺序找第一条命中的规则；没有匹配的就不限流。 */
    private RateLimitProperties.Rule matchRule(String uri) {
        for (RateLimitProperties.Rule rule : properties.getRules()) {
            if (rule.getPath() != null && pathMatcher.match(rule.getPath(), uri)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 取该维度的取值。
     *
     * <p><b>刻意不读 {@code X-Forwarded-For}</b>：那个头是客户端可以随意伪造的。
     * 在没有反向代理的情况下信任它，攻击者只要每次请求换一个 XFF 值，
     * **IP 限流就完全形同虚设**。
     *
     * <p>M9 引入 Gateway 之后要改：那时 XFF 由网关写入，可以信任，但必须
     * **只取网关追加的那一段**（而不是整串），否则客户端仍能在前面塞假值。
     * 这个改动记在这里，免得 M9 时忘了。
     */
    private String resolveDimensionValue(RateLimitProperties.Rule rule, HttpServletRequest request) {
        if (rule.getDimension() == RateLimitProperties.Dimension.USER) {
            Long userId = currentUserId();
            if (userId != null) {
                return String.valueOf(userId);
            }
            // 未登录：退回 IP。按用户限的接口在未登录时也得限，
            // 否则"先不登录地打"就成了绕过限流的方式
        }
        return clientIp(request);
    }

    /** 客户端 IP。不带端口——{@code getRemoteAddr()} 在 IPv6 下会带上端口号。 */
    private String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? "unknown" : remoteAddr;
    }

    private Long currentUserId() {
        // 委托给公共实现：M2 之前这段逻辑在本类与 AuthzService 里各有一份，
        // 已收拢到 wt-common 的 CurrentUser（内容域的帖子归属校验也要用同一份）
        return CurrentUser.id();
    }
}
