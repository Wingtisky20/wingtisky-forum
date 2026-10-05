package com.wingtisky.forum.forum.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wingtisky.forum.common.result.ErrorCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 的配置。
 *
 * <p><b>⚠️ 本类大量写法与网上多数教程不同，原因是那些教程还停留在 Boot 2 时代。</b>
 * 已按 6.5.11 的 jar 核实过：
 * <table>
 *   <tr><th>已删除 / 废弃的旧写法</th><th>本项目的写法</th></tr>
 *   <tr><td>{@code extends WebSecurityConfigurerAdapter} + {@code configure(HttpSecurity)}</td>
 *       <td>{@code SecurityFilterChain} Bean（适配器类已被删除）</td></tr>
 *   <tr><td>{@code authorizeRequests().antMatchers(...)}</td>
 *       <td>{@code authorizeHttpRequests(auth -> auth.requestMatchers(...))}</td></tr>
 *   <tr><td>{@code csrf().disable()}（无参链式）</td>
 *       <td>{@code csrf(AbstractHttpConfigurer::disable)}（Lambda DSL）</td></tr>
 * </table>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // 开启 @PreAuthorize。Task 5 的资源归属校验依赖它
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** 无需登录即可访问的路径。集中在这里，一眼能看全"哪些接口是敞开的"。 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider tokenProvider,
                                                   ObjectMapper objectMapper) throws Exception {

        AuthenticationEntryPoint entryPoint =
                (request, response, authException) ->
                        RestAuthErrorWriter.write(response, objectMapper, ErrorCode.UNAUTHORIZED);

        AccessDeniedHandler deniedHandler =
                (request, response, accessDeniedException) ->
                        RestAuthErrorWriter.write(response, objectMapper, ErrorCode.FORBIDDEN);

        http
                // 关掉 CSRF 保护是**因为不需要**，不是图省事：
                // CSRF 攻击利用的是浏览器会自动带上 Cookie；而本项目的凭据是
                // 前端显式放进 Authorization 头的 Bearer 令牌，浏览器不会自动携带。
                // 若将来改用 Cookie 存令牌，这一行必须改回来——届时 CSRF 防护就必需了。
                .csrf(AbstractHttpConfigurer::disable)

                // 无状态：不让 Spring Security 创建或使用 HttpSession。
                // 这是"令牌即凭据"的直接推论——服务端本来就不该记住谁登录过。
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // 个人主页是公开的：未登录也能看别人的主页
                        .requestMatchers(HttpMethod.GET, "/api/users/*").permitAll()
                        // M2：帖子列表、详情、某人的帖子——公开。
                        // 技术社区的内容本来就能匿名浏览，登录只在"要写东西"时才需要。
                        // ⚠️ 只放行 GET：同一个 /api/posts 路径上的 POST（发帖）仍然要认证。
                        .requestMatchers(HttpMethod.GET, "/api/posts", "/api/posts/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users/*/posts").permitAll()
                        // 评论列表同样公开；发评论（POST 同一路径）与删评论仍要认证
                        .requestMatchers(HttpMethod.GET, "/api/posts/*/comments").permitAll()
                        // 标签列表公开。标签只在发帖时产生，没有"新建标签"的接口
                        .requestMatchers(HttpMethod.GET, "/api/tags").permitAll()
                        // 剩下的全部需要认证。**用 anyRequest().authenticated() 而不是
                        // 逐个列出要保护的路径**——漏列一个的后果是那个接口完全敞开，
                        // 而这种漏洞不会报错，只会静静存在。
                        .anyRequest().authenticated())

                // 认证/授权失败时也返回统一响应体（否则前端要处理两种错误格式）
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))

                // 把 JWT 过滤器插在用户名密码过滤器之前——它负责建立认证上下文，
                // 必须早于后续依赖上下文的组件
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider),
                        UsernamePasswordAuthenticationFilter.class)

                // 关掉表单登录与 HTTP Basic：本服务是纯 API，没有登录页面；
                // 留着它们会让认证失败的响应变成 302 跳转或 WWW-Authenticate 挑战，
                // 而不是我们期望的 JSON
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
