package com.wingtisky.forum.forum.content.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 后台治理帖子的请求。**三个字段都可选**——给哪个就改哪个。
 *
 * <p>与作者改帖（{@code UpdatePostRequest}）是两个请求形状，因为它们本就是两件事：
 * 作者改的是**内容**（标题、正文、标签），版主改的是**属性**（置顶、加精、上下架）。
 * 合成一个请求的话，一个请求里既能改正文又能置顶，权限就没法按字段分了。
 *
 * @param topFlag      是否置顶；{@code null} 表示不改
 * @param featuredFlag 是否加精；{@code null} 表示不改
 * @param status       0 正常 / 1 下架。{@code null} 表示不改
 */
public record AdminPostUpdateRequest(

        Boolean topFlag,

        Boolean featuredFlag,

        /**
         * 用 {@code 0/1} 而不是布尔值：它与数据库列一一对应，而且**将来若多出第三种
         * 状态（比如"待审核"），入参的形状不用变**。
         *
         * <p>（读出去的那一侧不一样：详情响应里是布尔值 {@code offline}，
         * 与 {@code top} / {@code featured} 保持一致，读起来直白。
         * 写用序号、读用布尔，是有意的不对称——"设置一个值"和"读一个状态"
         * 本来就是两种意图。）
         *
         * <p>范围限制住（0 或 1）：**不能让调用方把状态改成 7**——
         * 那会让这条帖子既不在正常列表里、也不在任何"已下架"的处理路径上，
         * 变成一条谁都说不清该不该显示的数据。
         */
        @Min(value = 0, message = "状态只能是 0（正常）或 1（下架）")
        @Max(value = 1, message = "状态只能是 0（正常）或 1（下架）")
        Integer status
) {
}
