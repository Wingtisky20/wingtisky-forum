package com.wingtisky.forum.forum.user.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 令牌的签发与解析。
 *
 * <p><b>所用 API 已对着 jjwt 0.12.7 的 jar 核实过，不是凭记忆写的。</b>
 * 0.12 相比 0.11 改了整套方法名，网上大量示例仍是旧版：
 * <table>
 *   <tr><th>动作</th><th>0.12 的写法（本项目用的）</th><th>0.11 的旧写法（会编译失败）</th></tr>
 *   <tr><td>解析器</td><td>{@code Jwts.parser().verifyWith(key).build()}</td><td>{@code Jwts.parserBuilder().setSigningKey(key).build()}</td></tr>
 *   <tr><td>解析</td><td>{@code parseSignedClaims(token)}</td><td>{@code parseClaimsJws(token)}</td></tr>
 *   <tr><td>取载荷</td><td>{@code jwt.getPayload()}</td><td>{@code jwt.getBody()}</td></tr>
 * </table>
 */
@Component
public class JwtTokenProvider {

    /** 自定义声明：角色列表。放这里是为了"验签后即可构造权限，不必查库"。 */
    public static final String CLAIM_ROLES = "roles";

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        // 密钥太短时 hmacShaKeyFor 抛 WeakKeyException —— 让它在这里失败，
        // 而不是等上线后被人穷举出来。这是"启动即校验"的典型用法。
        this.signingKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发访问令牌。
     *
     * <p><b>角色写进载荷</b>，这样验签后就能直接构造权限，不用每次请求都查库——
     * 这正是"无状态"的价值所在，也是 ADR-0010 里拆分服务能简化的原因
     * （每个服务本地验签即可，不需要跨服务调用户接口）。
     *
     * <p>代价：改了角色要等令牌过期才生效（最长 {@code access-ttl}）。
     * 30 分钟可以接受；若不能接受，就得在每个请求查一次库，那无状态的好处就没了。
     */
    public IssuedToken issueAccessToken(Long userId, String username, List<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTtl());
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim(CLAIM_ROLES, roles)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();

        return new IssuedToken(token, jti, properties.getAccessTtl().toSeconds());
    }

    /**
     * 签发刷新令牌。
     *
     * <p>载荷里**只放用户标识与 jti**，不放角色——刷新令牌只用来换新的访问令牌，
     * 权限那一刻重新查库取，取到的是最新的。放进去反而会固化一个可能已过期的角色。
     */
    public IssuedToken issueRefreshToken(Long userId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getRefreshTtl());
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();

        return new IssuedToken(token, jti, properties.getRefreshTtl().toSeconds());
    }

    /**
     * 验签并解析。**签名不对、已过期、格式损坏都会抛 {@link JwtException}。**
     *
     * <p>刻意不在这里捕获异常转成业务错误：调用方的处理方式不同——
     * 过滤器选择"不设置认证、让后续规则返回 401"，而刷新接口选择"抛业务异常"。
     * 在这里吞掉会把两种场景抹平成一种。
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
