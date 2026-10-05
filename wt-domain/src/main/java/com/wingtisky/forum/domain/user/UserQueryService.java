package com.wingtisky.forum.domain.user;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 跨域查询用户展示信息的契约。
 *
 * <p><b>接口在这里，实现在 {@code forum-user}。</b>这是本项目"先画逻辑边界"的落地方式
 * （{@code docs/03-design/architecture.md §3.1}）：
 *
 * <pre>
 *   forum-user  ──依赖──▶  wt-domain  ◀──依赖──  forum-content
 *   （提供实现）              （只放契约）            （只调接口）
 * </pre>
 *
 * 两边都只认识 {@code wt-domain}，**互相不认识**。所以内容域写帖子时，
 * 即便要显示作者昵称，也不会、也不该去依赖用户域——M9 把三个域拆成三个进程时，
 * 这份约定就是拆分能成立的依据。
 *
 * <p>这条边界由 {@code ArchitectureTest} 的规则 2 强制，不是靠自觉。
 *
 * <h2>为什么批量方法是必须的，不是提前优化</h2>
 *
 * 调用这个契约的典型场景是**分页列表**：一页 20 篇帖子，就有 20 个作者要补信息。
 * 如果契约只提供单条查询，调用方**必然**写成循环——那是 N+1，
 * 而 N+1 最麻烦的地方是**它不会报错**，只在数据量上来之后表现为"列表越来越慢"，
 * 到那时也很难一眼看出是这里的问题。
 *
 * 把 {@link #findBriefs} 放在契约里，是**让正确的用法成为最省事的用法**。
 * 这比事后靠 code review 拦 N+1 可靠得多。
 *
 * <h2>这是 M3 的第一个缓存对象</h2>
 *
 * 用户信息**读多写少**，且"改昵称"是一个**明确的失效事件**——正是亮点 1（多级缓存）
 * 要处理的那种数据。M3 加缓存时只需要在实现里加，**调用方一行不用改**；
 * 若现在让每个调用方自己去查库，M3 就要到处改。
 */
public interface UserQueryService {

    /**
     * 按 ID 批量取用户展示信息。
     *
     * <p><b>查不到的 id 不会出现在返回的 Map 里</b>——本方法**不补位**。
     * 补 {@code null} 或补一个占位 {@code UserBrief} 会把两种情况混在一起：
     * "这个用户被删了"和"这个用户的名字查不到"。调用方自己要能区分这两种，
     * 所以由调用方用 {@code getOrDefault} 决定怎么显示（比如"已注销用户"）。
     *
     * @param userIds 用户 ID 集合。**传空集合是合法的**，会直接返回空 Map
     *                （而不是去拼一条 {@code IN ()} 的非法 SQL）
     * @return 以用户 ID 为键的投影，只包含**查到了的**那些
     */
    Map<Long, UserBrief> findBriefs(Collection<Long> userIds);

    /**
     * 取单个用户的展示信息。
     *
     * <p>找不到返回 {@link Optional#empty()}，而不是 null、也不抛异常：
     * "用户不存在"在展示场景里是**正常情况**（帖子作者注销了），不是错误。
     * 用异常表达它，会逼着每个调用方写 try-catch 来当 if 用。
     *
     * @param userId 用户 ID，传 {@code null} 时返回空（不去查库）
     */
    Optional<UserBrief> findBrief(Long userId);
}
