package com.wingtisky.forum.domain.user;

/**
 * 用户信息的**最小投影**——别的域要显示"这条帖子是谁发的"时，拿的是它。
 *
 * <p><b>这是一个跨域契约类型，不是实体。</b>它只带展示需要的东西：
 * {@code id} 用来标识，{@code nickname} 与 {@code avatar} 用来显示。
 *
 * <h2>为什么不含 email、更不含 password</h2>
 *
 * 把 {@code User} 实体放进本模块，等于把用户域的内部结构变成**公共 API**：
 * 此后用户表加一个字段，就得先想"内容域会不会受影响"。
 * 而 {@code password} 尤其不能出现在契约里——它是一旦泄漏就无法挽回的东西，
 * 让它有机会跨模块流动是没必要的风险。
 *
 * <h2>为什么是 record 而不是普通类</h2>
 *
 * 它是个**只读投影**：拿到的就是某个时刻的用户展示信息，没有任何理由在传递过程中被改。
 * record 天然不可变、没有 setter、{@code equals}/{@code hashCode} 由字段生成，
 * 语义与"投影"完全吻合。用普通类的话，它能被随手改，而这正是契约里最不该有的东西。
 *
 * <h2>它不由 MyBatis 直接映射</h2>
 *
 * record 没有无参构造，MyBatis 要映射它就得显式写 {@code <constructor>} resultMap——
 * 是个额外的心智负担。本项目的做法是：mapper 查出**只填三个字段的 {@code User}**，
 * 由 {@code UserQueryServiceImpl} 转一次。转换逻辑是纯函数，可以单测，
 * 而 SQL 那边保持最朴素的写法。
 *
 * @param id       用户 ID，不会为 null
 * @param nickname 昵称，不会为 null（{@code t_user.nickname} 是 NOT NULL）
 * @param avatar   头像 URL，**可能为 null**（{@code t_user.avatar} 可空）——
 *                 调用方要准备好一个默认头像
 */
public record UserBrief(Long id, String nickname, String avatar) {
}
