package com.wingtisky.forum.forum.user.security;

import com.wingtisky.forum.infra.redis.RedisKey;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 刷新令牌的服务端记录。**这是整套认证里唯一有状态的地方。**
 *
 * <p><b>为什么需要它</b>：JWT 是自包含的，签出去之后服务端手里没有记录，
 * 因此**无法在到期前撤回**。想撤回就得存状态——而存了状态就不再"无状态"。
 *
 * <p><b>本项目的选择是不去对抗这个矛盾，而是把它放到该放的地方</b>（设计 §3.2）：
 * <ul>
 *   <li>访问令牌：**纯无状态**，服务端不留任何记录，验签即通过，可撤销性为零</li>
 *   <li>刷新令牌：**有状态**，在这里存 jti，注销 = 删掉它</li>
 * </ul>
 * 于是注销的效果是"拿不到新令牌了，而手上那张旧的**最多再活 access-ttl**"。
 * 这个窗口是明确接受的代价，换来的是每个正常请求都不查任何存储。
 *
 * <p><b>为什么不存整个令牌串而只存 jti</b>：令牌串等于凭据，存进 Redis 会让
 * "Redis 泄露"直接等于"账号被盗"。存 jti 只能用来比对，泄露了也无法伪造令牌。
 */
@Component
public class RefreshTokenStore {

    /** 用 String 模板而不是默认的 JDK 序列化模板：存的是普通字符串，
     *  用 JDK 序列化会写出一串带类名的二进制前缀，`redis-cli` 里根本读不了。 */
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RefreshTokenStore(StringRedisTemplate redis, JwtProperties properties) {
        this.redis = redis;
        this.ttl = properties.getRefreshTtl();
    }

    /** 记录该用户当前有效的刷新令牌标识。重复登录会覆盖——即"后登录的顶掉先登录的"。 */
    public void save(Long userId, String jti) {
        redis.opsForValue().set(RedisKey.refreshToken(userId), jti, ttl);
    }

    /**
     * 比对 jti 是否为该用户当前有效的那个。
     *
     * <p>必须比对而不是"存在即有效"：否则用户注销后重新登录，**注销前泄露出去的
     * 那张刷新令牌仍然能用**——因为它对应的 jti 曾经存在过。
     */
    public boolean isCurrent(Long userId, String jti) {
        return Objects.equals(jti, redis.opsForValue().get(RedisKey.refreshToken(userId)));
    }

    /** 注销。删掉之后该用户的刷新令牌立即失效。 */
    public void remove(Long userId) {
        redis.delete(RedisKey.refreshToken(userId));
    }
}
