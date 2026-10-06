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
         * 用 {@code 0/1} 而不是布尔值：它与数据库列、以及详情响应里的 {@code status}
         * 是同一个东西。换成 {@code offline: true} 更好读，但那就多了一层
         * "接口说 true、库里存 1"的对应关系要维护。
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
