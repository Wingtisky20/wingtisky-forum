package com.wingtisky.forum.forum.user.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从 {@code Authorization: Bearer <token>} 中取出访问令牌并建立认证上下文。
 *
 * <p><b>这个过滤器不拒绝任何请求。</b>它只做两件事：能验通就设置认证信息，验不通就什么都不做。
 * 至于"没认证的请求能不能访问这个接口"，交给 {@code SecurityConfig} 里的授权规则去判断。
 *
 * <p><b>为什么这样分工</b>：如果过滤器自己返回 401，那就等于把"哪些接口不需要登录"
 * 这个规则写在了两个地方——过滤器和配置里。两处迟早会不一致，而这种不一致的表现
 * 是"某个公开接口突然要登录了"，或者是更糟的"某个该保护的接口被放过了"。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /**
     * Spring Security 的角色前缀。
     *
     * <p>{@code hasRole('ADMIN')} 比对的是 {@code ROLE_ADMIN}，前缀由框架自动补。
     * 构造 {@link SimpleGrantedAuthority} 时必须自己写上——**少了它，
     * 所有 hasRole 判断都会失败**，而症状是"明明有权限却说没权限"，很难查。
     */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            authenticate(token);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Claims claims = tokenProvider.parse(token);

            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            List<String> roles = rolesOf(claims);

            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                    .toList();

            // 三个参数：principal / credentials / authorities。
            // principal 放 userId（业务里最常用的标识），credentials 传 null——
            // 令牌已经验过了，没有必要再留着它，留着反而有被日志打出来的风险。
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authentication.setDetails(username);

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException e) {
            // 令牌无效/过期/被篡改。**只记 debug 不记 warn**：过期是每天都会发生的
            // 正常现象（用户放久了没操作），记 warn 会把日志淹没。
            // 清空上下文是必要的：容器复用线程，不清会沿用上一个请求的认证信息。
            SecurityContextHolder.clearContext();
            log.debug("访问令牌不可用: {}", e.getMessage());
        }
    }

    /**
     * 不直接用 {@code claims.get("roles", List.class)}——JJWT 反序列化后是
     * {@code List<?>}，元素类型不保证是 String，强转会在运行时抛 ClassCastException。
     */
    @SuppressWarnings("unchecked")
    private List<String> rolesOf(Claims claims) {
        Object raw = claims.get(JwtTokenProvider.CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
