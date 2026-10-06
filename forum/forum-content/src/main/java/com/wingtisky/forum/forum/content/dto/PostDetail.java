package com.wingtisky.forum.forum.content.dto;

import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.forum.content.cache.CachedPostDetail;

import java.time.LocalDateTime;
import java.util.List;

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
        List<TagView> tags,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    /**
     * 从**缓存对象**组装详情——M3 起详情页走这条路。
     *
     * <p>入参是 {@link CachedPostDetail} 而不是 {@code Post} 实体，因为读路径上
     * 已经不再直接查库了：公共内容由两级缓存供给。缓存对象里本来就带着组装
     * 这个 DTO 需要的全部字段（它就是从帖子实体切出来的）。
     *
     * @param viewCount 由**调用方传进来**，而不是从缓存对象上取。
     *                  原因是浏览数每次读都在变，压根不在缓存里——它由 Redis
     *                  计数器负责。传进来才能让这个 DTO 不依赖"浏览数存在哪"。
     * @param liked     因人而异，**不进缓存**，每次现查
     * @param collected 同上
     */
    public static PostDetail from(CachedPostDetail post, int viewCount,
                                  boolean liked, boolean collected) {
        return new PostDetail(
                post.id(),
                post.title(),
                post.content(),
                post.authorId(),
                post.author(),
                post.top(),
                post.featured(),
                post.offline(),
                viewCount,
                post.likeCount(),
                post.commentCount(),
                post.collectCount(),
                liked,
                collected,
                // 兜一层 null：list 类型的字段一旦序列化成 `"tags": null`，
                // 前端每个用到它的地方都得写 `|| []`——漏一处就是一次白屏。
                // 在这一个边界上归一成空列表，比让所有调用方都记得判空便宜。
                post.tags() == null ? List.of() : post.tags(),
                post.createTime(),
                post.updateTime());
    }
}
