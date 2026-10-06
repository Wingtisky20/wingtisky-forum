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
 * @param liked  **当前登录的人**点过赞吗。未登录时恒为 {@code false}——
 *               所以前端在未登录时不能靠它判断"这条帖子没人点赞"
 * @param collected 同上，收藏
 * @param offline **这条帖子已被版主下架**。
 *                对别人恒为 {@code false}——他们根本打不开（见 {@code PostService.visibleTo}）。
 *                它存在的唯一目的是：**让作者知道自己那篇去哪了**。
 *                没有它的话，"作者仍能打开自己的帖子"这条规则只做了一半——
 *                他打开了，但看到的和正常帖子一模一样，仍然不知道发生了什么。
 */
public record PostDetail(
        Long id,
        String title,
        String content,
        Long authorId,
        UserBrief author,
        boolean top,
        boolean featured,
        boolean offline,
        int viewCount,
        int likeCount,
        int commentCount,
        int collectCount,
        boolean liked,
        boolean collected,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public static PostDetail from(Post post, UserBrief author, boolean liked, boolean collected) {
        return new PostDetail(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getAuthorId(),
                author,
                post.getTopFlag() == 1,
                post.getFeaturedFlag() == 1,
                post.isOffline(),
                post.getViewCount(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getCollectCount(),
                liked,
                collected,
                post.getCreateTime(),
                post.getUpdateTime());
    }
}
