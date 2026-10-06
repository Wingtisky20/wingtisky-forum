package com.wingtisky.forum.forum.content.dto;

import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.forum.content.entity.Comment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条**顶层评论**，连同它下面的回复。
 *
 * <p>回复直接挂在对象里、**不单独分页**：两级模型下，一条顶层评论的回复数量
 * 通常是个位到几十条，跟着一起返回最省事。真出现"一楼几千条回复"的情况时，
 * 再给回复单独开分页接口（现在不做，YAGNI）。
 *
 * @param replies 该楼的全部回复，按时间正序。**不会是 null**，没有回复时是空列表
 * @param author  可能为 null（作者已注销）
 */
public record CommentView(
        Long id,
        Long authorId,
        UserBrief author,
        String content,
        LocalDateTime createTime,
        List<CommentReply> replies
) {

    public static CommentView from(Comment comment, UserBrief author, List<CommentReply> replies) {
        return new CommentView(
                comment.getId(),
                comment.getAuthorId(),
                author,
                comment.getContent(),
                comment.getCreateTime(),
                replies);
    }
}
