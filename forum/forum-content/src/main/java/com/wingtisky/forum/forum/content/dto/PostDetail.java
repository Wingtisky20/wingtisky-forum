package com.wingtisky.forum.forum.content.dto;

import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.forum.content.entity.Post;

import java.time.LocalDateTime;

/**
 * 帖子详情页的形状。
 *
 * <p>与 {@link PostListItem} 的唯一区别是**它带正文、不带摘要**——
 * 摘要本来就是给列表用的缩略版，详情页有全文，摘要没有意义。
 * 分成两个类型而不是一个（正文可为 null 的那种），是因为
 * "一个字段有时有值有时没有"会让每个读它的人都要先判断一次能不能用。
 *
 * @param author 可能为 null，见 {@link PostListItem#author()} 的说明
 */
public record PostDetail(
        Long id,
        String title,
        String content,
        Long authorId,
        UserBrief author,
        boolean top,
        boolean featured,
        int viewCount,
        int likeCount,
        int commentCount,
        int collectCount,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public static PostDetail from(Post post, UserBrief author) {
        return new PostDetail(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getAuthorId(),
                author,
                post.getTopFlag() == 1,
                post.getFeaturedFlag() == 1,
                post.getViewCount(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getCollectCount(),
                post.getCreateTime(),
                post.getUpdateTime());
    }
}
