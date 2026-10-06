package com.wingtisky.forum.forum.content.dto;

import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.forum.content.entity.Comment;

import java.time.LocalDateTime;

/**
 * 一条**回复**。
 *
 * <p>它和 {@link CommentView} 是两个类型、而不是同一个类型的自引用，
 * 是**故意的**：两个类型把"只有两级"这件事写进了类型系统——
 * 回复里**没有** {@code replies} 字段，所以不可能有人给它挂第三层。
 * 用一个自引用的 {@code CommentView} 的话，那个可能性一直在，
 * 而且要靠 review 去发现有没有人用错。
 *
 * @param parentId 它直接回复的那条评论。**可能是顶层评论，也可能是另一条回复**——
 *                 后者说明这是一条"回复的回复"，被拍平到了同一层
 * @param author   可能为 null（作者已注销），处理方式同帖子
 */
public record CommentReply(
        Long id,
        Long authorId,
        UserBrief author,
        String content,
        Long parentId,
        LocalDateTime createTime
) {

    public static CommentReply from(Comment comment, UserBrief author) {
        return new CommentReply(
                comment.getId(),
                comment.getAuthorId(),
                author,
                comment.getContent(),
                comment.getParentId(),
                comment.getCreateTime());
    }
}
