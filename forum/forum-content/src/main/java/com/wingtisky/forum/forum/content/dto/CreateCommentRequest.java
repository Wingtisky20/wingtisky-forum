package com.wingtisky.forum.forum.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发评论 / 回复的请求。**同一个请求形状覆盖两种情况**：
 * 不传 {@code parentId} 是顶层评论，传了就是回复某条评论。
 *
 * <p>不为"回复"单开一个接口，是因为它们在业务上是同一件事——
 * 只是"挂在哪"不同。两个接口会让前端的发送逻辑分叉，而分叉的业务规则
 * （长度限制、帖子必须存在、计数维护）也要写两遍。
 *
 * @param parentId 被回复的评论 ID；**为 {@code null} 表示这是一条顶层评论**。
 *                 注意它可以指向**一条回复**——回复的回复会被拍平到同一个顶层评论下
 *                 （两级模型，ADR-0018），这是允许的，不是错误
 * @param content  内容，不能为空白，最多 1000 字（与数据库列长一致）
 */
public record CreateCommentRequest(

        Long parentId,

        @NotBlank(message = "评论内容不能为空")
        @Size(max = 1000, message = "评论最多 1000 个字")
        String content
) {
}
