package com.wingtisky.forum.forum.user.security;

import com.wingtisky.forum.common.security.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * 资源归属校验。**与 RBAC 是两件事，别混。**
 *
 * <table>
 *   <tr><th>层</th><th>回答的问题</th><th>手段</th></tr>
 *   <tr><td>RBAC</td><td>"你<strong>有没有资格</strong>做这类事"</td>
 *       <td>{@code hasRole('ADMIN')}</td></tr>
 *   <tr><td>资源归属</td><td>"<strong>这件事的东西</strong>是不是你的"</td>
 *       <td>本类</td></tr>
 * </table>
 *
 * <p>管理员和作者**都能**删帖，但普通用户不能删**别人的**帖。RBAC 只知道前者，
 * 后者必须比对数据——这是两个不同的问题。
 *
 * <p><b>为什么收进一个 Bean，而不是在各处手写 if</b>：手写的失败模式是**静默的**——
 * 漏写一处不会有任何报错，直到被人利用。收进一处之后，新增接口时"忘了加校验"
 * 会变成一个显式的遗漏（代码 review 和测试都能看出来），而不是悄悄少了一行。
 *
 * <p><b>Bean 名显式定为 {@code authz}</b>：SpEL 里写 {@code @authz.isOwner(#id)}，
 * 比默认的 {@code @authzService} 短，也在调用点上更醒目。
 */
@Component("authz")
public class AuthzService {

    /**
     * 当前登录用户是否就是资源所有者。
     *
     * <p>未登录时返回 {@code false}（而不是抛异常）：本方法会被 SpEL 调用，
     * 保持它"只回答是或否"最简单。至于"未登录该返回 401 还是 403"，
     * 由 Spring Security 的授权规则决定，不该在这里判断。
     */
    public boolean isOwner(Long ownerId) {
        Long currentUserId = CurrentUser.id();
        return currentUserId != null && currentUserId.equals(ownerId);
    }

    /**
     * 当前登录用户的 ID；未登录返回 {@code null}。
     *
     * <p><b>⚠️ 注意它的语义：入参是"用户 ID"。</b>本方法比的是
     * "当前登录用户是不是这个人"。
     *
     * <p>要判断"这条<strong>帖子</strong>是不是我发的"，**不能**把帖子 ID 传进来——
     * 那比的是"帖子 ID 是否等于我的用户 ID"，几乎永远为假（作者自己也被拒），
     * 而且万一两者恰好相等就会错误放行。帖子那类资源用内容域自己的
     * {@code PostAuthzService}（Bean 名 {@code postAuthz}）。
     *
     * <p>实现委托给 {@link CurrentUser#id()}——M2 之前这段逻辑在本类和限流拦截器里
     * 各有一份拷贝，已收拢到一处。
     */
    public Long currentUserId() {
        return CurrentUser.id();
    }
}
