package com.wingtisky.forum.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * "现在这个请求是谁发的"。
 *
 * <p><b>这是机制，不是策略</b>（architecture.md §4.3）——它只回答"当前登录的是谁"，
 * 不回答"他能不能做这件事"。后者各域自己判断（比如用户域判"这条资料是不是你的"，
 * 内容域判"这条帖子是不是你发的"）。
 *
 * <p><b>为什么放到 {@code wt-common} 而不是某个业务域</b>：M2 之前这段逻辑在项目里
 * **已经有两份几乎一样的拷贝**（用户域的 {@code AuthzService} 与限流拦截器）。
 * 再给内容域抄第三份的话，将来"principal 不再是 Long"这类改动就要改三处，
 * 而漏改的表现是**某个域的鉴权静默失效**。
 *
 * <h2>为什么 principal 是个 Long</h2>
 *
 * {@code JwtAuthenticationFilter} 校验令牌后，把 userId 放进了认证上下文的 principal。
 * 这样后续任何地方都能直接从上下文拿到用户 id，**不需要再查库**——
 * 这正是 JWT 无状态的价值（ADR-0013）。
 *
 * <p>这里用 {@code instanceof} 而不是强制转型：将来若有人把 principal 换成
 * {@code UserDetails}，强转会抛 {@code ClassCastException}，而这里会安静地返回 null。
 * 返回 null 的表现是"未登录"，是个更可控的失败方式。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * 当前登录用户的 ID；**未登录返回 {@code null}**。
     *
     * <p>为什么不抛异常：本方法会被 SpEL 表达式调用（{@code @PreAuthorize}），
     * 保持"只回答是或否/有没有"最简单。至于"未登录该返回 401 还是 403"，
     * 由 Spring Security 的授权规则决定，不该在这里判断。
     */
    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return (principal instanceof Long userId) ? userId : null;
    }
}
