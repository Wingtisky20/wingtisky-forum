package com.wingtisky.forum.forum.user.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.user.dto.LoginResult;
import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.security.IssuedToken;
import com.wingtisky.forum.forum.user.ratelimit.LocalRateLimiter;
import com.wingtisky.forum.forum.user.ratelimit.RateLimitProperties;
import com.wingtisky.forum.forum.user.security.JwtTokenProvider;
import com.wingtisky.forum.forum.user.security.RefreshTokenStore;
import com.wingtisky.forum.infra.redis.RedisKey;
import com.wingtisky.forum.infra.redis.SlidingWindowRateLimiter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 认证流程：登录、刷新、注销。
 *
 * <p><b>与 {@link UserService} 的分工</b>：UserService 管"这个用户名密码对不对"，
 * AuthService 管"对了之后发给谁什么令牌、怎么撤销"。混在一起会让领域服务开始
 * 知道令牌这种传输层概念。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserService userService;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final RateLimitProperties rateLimitProperties;
    private final SlidingWindowRateLimiter redisLimiter;
    private final LocalRateLimiter localLimiter;

    public AuthService(UserService userService,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenStore refreshTokenStore,
                       RateLimitProperties rateLimitProperties,
                       SlidingWindowRateLimiter redisLimiter,
                       LocalRateLimiter localLimiter) {
        this.userService = userService;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.rateLimitProperties = rateLimitProperties;
        this.redisLimiter = redisLimiter;
        this.localLimiter = localLimiter;
    }

    /**
     * 登录。凭据校验失败的各种情形由 {@code UserService.authenticate} 决定（含防枚举的两条措施）。
     *
     * <p><b>限流放在最前面</b>：再往下走就是 BCrypt 比对（每次约 100ms）。
     * 放在后面的话，撞库请求已经打到密码校验上了，限流来得太晚。
     */
    public LoginResult login(String username, String rawPassword) {
        checkUsernameRateLimit(username);

        User user = userService.authenticate(username, rawPassword);
        List<String> roles = userService.getRoleCodes(user.getId());
        return issueTokens(user, roles);
    }

    /**
     * 按**用户名**限流。与限流拦截器里的 IP 维度**不是重复**：
     *
     * <ul>
     *   <li>按 IP —— 挡"同一台机器试很多账号"</li>
     *   <li>按用户名 —— 挡"很多台机器试同一个账号"（撞库的经典形态）</li>
     * </ul>
     *
     * <p><b>为什么它不在限流拦截器里</b>（M1 设计稿 §5.4 论证过）：拦截器运行在
     * Controller 之前，**拿不到请求体里的用户名**；要在那里拿，就得把请求包一层
     * （{@code ContentCachingRequestWrapper}），而请求体是一次性的，读完后续
     * {@code @RequestBody} 就拿不到了。放到这里，用户名已经是方法参数。
     *
     * <p>代价是限流逻辑分了两处。**这是有意的取舍，不是随手写的**——
     * 所以这段注释要留在这里，免得下一个人把它挪回拦截器里，一挪就坏。
     *
     * <p><b>Redis 挂掉时退回本地内存</b>，不放行也不全拒：
     * 防撞库是这一维度限流的**唯一目的**，放行等于把门打开；
     * 而全拒会让谁都登不进来。退回单机计数精度差些，但保护还在
     * （与登录的 IP 维度同策略，见 ADR-0014）。
     */
    private void checkUsernameRateLimit(String username) {
        if (username == null || username.isBlank()) {
            return;
        }

        RateLimitProperties.LoginUsernameLimit rule = rateLimitProperties.getLoginUsername();
        String key = RedisKey.rateLimit("username", username, "POST", "/api/auth/login");

        boolean allowed;
        try {
            allowed = redisLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow().toMillis())
                    .allowed();
        } catch (RuntimeException e) {
            // 记 ERROR 而不是 WARN：限流正在失效，这是需要被看到的信号
            log.error("用户名维度限流降级：Redis 不可用，退回本地内存。key={}", key, e);
            allowed = localLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow().toMillis());
        }

        if (!allowed) {
            log.warn("触发用户名维度限流: username={}, limit={}/{}",
                    username, rule.getLimit(), rule.getWindow());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    /**
     * 用刷新令牌换一对新令牌。
     *
     * <p><b>换的时候会把旧的刷新令牌作废</b>（下面 save 会覆盖 Redis 里的 jti）——
     * 这叫**刷新令牌轮换**。不轮换的话，一张刷新令牌在 7 天里可以无限次使用；
     * 一旦泄露，攻击者能一直续期，而真正的用户毫无察觉。
     * 轮换后，被偷走的那张在用户下次刷新时就失效了。
     *
     * <p><b>用户的角色与状态在这里重新查库</b>，不吃旧令牌里的信息——
     * 这样"改角色"和"封禁账号"能在刷新时（最长 30 分钟后）生效，
     * 而不是等 7 天。
     */
    public LoginResult refresh(String refreshToken) {
        Claims claims = parseRefreshToken(refreshToken);

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            // subject 不是数字说明令牌被改过（但签名还过得了？不可能）或者是我们自己签错了。
            // 无论哪种，都不该给出更具体的信息。
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        if (!refreshTokenStore.isCurrent(userId, claims.getId())) {
            // 三种情况会走到这里：已注销、已被轮换掉、这个 jti 从来没发过。
            // 对外都是同一句话——区分它们等于告诉攻击者"你偷的这张是不是最新的"。
            log.debug("刷新令牌已失效: userId={}", userId);
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        User user = userService.getById(userId);
        if (!user.isActive()) {
            // 账号在令牌有效期内被封禁：此时不能续期，并顺手清掉刷新令牌
            refreshTokenStore.remove(userId);
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        List<String> roles = userService.getRoleCodes(userId);
        return issueTokens(user, roles);
    }

    /**
     * 注销。
     *
     * <p><b>它的效果是"拿不到新令牌"，而不是"立刻踢下线"</b>——手上那张访问令牌
     * 因为是无状态的，会一直有效到自然过期（最长 {@code access-ttl}）。
     * 这是设计 §3.2 明确接受的代价：要压到零就得让每个请求都查一次黑名单，
     * 那 ADR-0010 里"每个服务本地验签"的简化就不成立了。
     *
     * <p>幂等：没登录过也能调用，不报错。
     */
    public void logout(Long userId) {
        refreshTokenStore.remove(userId);
        log.info("用户注销: userId={}", userId);
    }

    private Claims parseRefreshToken(String refreshToken) {
        try {
            return tokenProvider.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名不对、格式损坏 —— 对外一律是"未登录或登录已过期"。
            // 不把 jjwt 的异常信息透出去：那是实现细节，对调用方没有价值。
            log.debug("刷新令牌解析失败: {}", e.getMessage());
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    private LoginResult issueTokens(User user, List<String> roles) {
        IssuedToken access = tokenProvider.issueAccessToken(user.getId(), user.getUsername(), roles);
        IssuedToken refresh = tokenProvider.issueRefreshToken(user.getId());
        refreshTokenStore.save(user.getId(), refresh.jti());

        return new LoginResult(
                access.token(), refresh.token(),
                access.expiresInSeconds(), refresh.expiresInSeconds());
    }
}
