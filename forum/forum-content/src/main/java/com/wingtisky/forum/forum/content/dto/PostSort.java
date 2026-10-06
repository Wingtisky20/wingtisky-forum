package com.wingtisky.forum.forum.content.dto;

/**
 * 帖子列表的排序方式。
 *
 * <p><b>用枚举而不是字符串</b>：字符串排序参数最终要拼进 {@code ORDER BY}，
 * 而拼 SQL 只有两种写法——用 {@code #&#123;&#125;}（占位符，但 ORDER BY 的列名不能用占位符）
 * 或用 {@code $&#123;&#125;}（直接拼接，**可被注入**）。用枚举就能让 SQL 侧只出现
 * 硬编码的两种排法（见 {@code PostMapper.xml} 的 {@code <choose>}），
 * 外部传进来的值**根本到不了 SQL 里**。
 */
public enum PostSort {

    /** 最新：置顶优先，然后按发布时间倒序。 */
    LATEST,

    /**
     * 最热：置顶优先，然后按热度分倒序。
     *
     * <p>热度公式是 {@code 评论×3 + 点赞×2 + 收藏×1}。
     * <b>这个系数是拍出来的，不是算出来的</b>——它是初始值，
     * 要在 M6 用真实数据与 {@code EXPLAIN} 校准（设计稿 §10 的 ⑩）。
     */
    HOT
}
