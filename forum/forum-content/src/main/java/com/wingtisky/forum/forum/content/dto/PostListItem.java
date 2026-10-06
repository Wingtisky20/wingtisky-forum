package com.wingtisky.forum.forum.content.dto;

import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.forum.content.entity.Post;

import java.time.LocalDateTime;

/**
 * 列表里的一条帖子。
 *
 * <p><b>它和 {@code Post} 实体的区别，是"不该出去的东西不在这里"</b>：
 * 没有 {@code deleted}，也没有 {@code content}——列表本来就不该带正文
 * （设计稿 §2.6：列表页永远不碰正文那个大字段）。
 * 这不是"顺手少放几个字段"，而是**用类型把"列表页不许读正文"这条规则钉住**：
 * 想带正文就得出另一个类型，那时你会停下来想一下是不是真的要带。
 *
 * <p>字段数值（浏览数、点赞数等）在表里都是 {@code NOT NULL DEFAULT 0}，
 * 所以这里用基本类型 {@code int}——从数据库读出来的行不可能是 null。
 *
 * @param authorId 作者 ID。**始终有值**，即使作者已注销（表里就存着这个 id）
 * @param author   作者的昵称与头像。**可能为 null**——作者把账号注销（逻辑删除）后，
 *                 批量查询查不到他，此时前端应显示"已注销用户"而不是崩溃
 */
public record PostListItem(
        Long id,
        String title,
        String summary,
        Long authorId,
        UserBrief author,
        boolean top,
        boolean featured,
        int viewCount,
        int likeCount,
        int commentCount,
        int collectCount,
        LocalDateTime createTime
) {

    public static PostListItem from(Post post, UserBrief author) {
        return new PostListItem(
                post.getId(),
                post.getTitle(),
                post.getSummary(),
                post.getAuthorId(),
                author,
                post.getTopFlag() == 1,
                post.getFeaturedFlag() == 1,
                post.getViewCount(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getCollectCount(),
                post.getCreateTime());
    }
}
