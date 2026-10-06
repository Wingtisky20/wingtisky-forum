package com.wingtisky.forum.forum.content.cache;

import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.forum.content.dto.TagView;
import com.wingtisky.forum.forum.content.entity.Post;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情里**可以缓存的那一部分**。它是 {@code PostDetail} 的一个真子集。
 *
 * <p><b>它是怎么切出来的</b>——判据只有一句（设计稿 §2.1）：
 *
 * <blockquote>这个值会不会因为一次「读」而改变？<br>
 * 会 → 不能进缓存，得走计数器；不会 → 能进缓存，由写负责删。</blockquote>
 *
 * <p>按这句话切下来，{@code PostDetail} 的 17 个字段分成四堆：
 *
 * <table>
 *   <tr><th>哪一堆</th><th>字段</th><th>去哪</th></tr>
 *   <tr><td>几乎不变</td><td>标题、正文、作者、标签、置顶/加精、创建与更新时间</td>
 *       <td><b>进这里</b>（只有编辑与治理会动）</td></tr>
 *   <tr><td>变得比较多</td><td>三个计数（赞 / 藏 / 评）</td>
 *       <td><b>也进这里</b>（点赞评论会动，但远低于读的频率）</td></tr>
 *   <tr><td>每次读都在变</td><td>{@code viewCount}</td>
 *       <td>**不进**——放进缓存的话，它从存在那一刻起就是脏的。走 Redis 计数器</td></tr>
 *   <tr><td>因人而异</td><td>{@code liked} / {@code collected}</td>
 *       <td>**不进**——它们是**用户私有状态**，塞进公共缓存是"命中率"和"越权风险"
 *           两样一起赔（缓存串号 = 别人看到你点过赞）</td></tr>
 * </table>
 *
 * <p><b>为什么必须带上 {@link #visible}</b>：缓存的是**数据**，不是**权限判定的结果**。
 * 命中缓存时**仍然要重新做可见性判定**，否则已下架的帖子被缓存之后，
 * 匿名用户也能拿到——那是越权，不是缓存问题。而判定要用到"这条帖子本身可不可见"，
 * 所以它必须跟着进缓存。（{@code visible} 是 {@code Post.isVisible()} 的结论，
 * 即"未被下架、未被删除"。）
 *
 * <p><b>为什么用 record</b>：L1 里存的是**对象引用**而非副本（设计稿 §7）。
 * 可变对象被调用方一改，缓存就被静默改掉了，而且没有任何代码删过这个 key、日志里也查不出来。
 * {@code record} 天然不可变；剩下唯一可变的 {@code List} 字段在构造时拷一份。
 *
 * @param visible     这条帖子本身可不可见（未被下架、未被删除）。
 *                    **可见性判定要用它**——命中缓存也必须重新判鉴权
 * @param offline     已被版主下架。对别人恒为 false（他们根本打不开，见 {@code PostService.visibleTo}）；
 *                    它存在的唯一目的是让作者知道"我那篇去哪了"
 * @param likeCount   点赞数。**它在这里，所以点赞必须负责删这个 key**（见设计稿 §3.3 的失效清单）
 * @param commentCount 评论数，同上
 * @param collectCount 收藏数，同上
 * @param tags        标签。构造时会 `List.copyOf` 拷一份——缓存里存的是引用，不拷会被改掉
 */
public record CachedPostDetail(
        Long id,
        String title,
        String content,
        Long authorId,
        UserBrief author,
        boolean visible,
        boolean top,
        boolean featured,
        boolean offline,
        int likeCount,
        int commentCount,
        int collectCount,
        List<TagView> tags,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    /**
     * 紧凑构造器：把可变的那个字段拷成不可变的。
     *
     * <p>不拷的话，"从缓存取出来的对象"和"缓存里存着的对象"是**同一个**，
     * 调用方一改它就跟着变——而且**没有任何代码删过这个 key**，
     * 于是问题只会在很久以后以"数据莫名其妙不对"的形式出现。
     */
    public CachedPostDetail {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /**
     * 从**原料**组装，而不是从 {@code PostDetail} 裁剪。
     *
     * <p>这条路是有意选的（见 2026-10-06 的 Step 0）：
     * 从 {@code PostDetail} 裁剪的话，等于**先把用户态算出来了再丢掉**——
     * 白查两次库是小事，"忘了丢哪个字段"是大事：
     * 缓存里混进某个人的点赞状态，下一个命中缓存的用户就会看到别人的状态。
     * 从原料组装则天然只含公共内容，**不可能**混进用户态。
     */
    public static CachedPostDetail from(Post post, UserBrief author, List<TagView> tags) {
        return new CachedPostDetail(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getAuthorId(),
                author,
                post.isVisible(),
                post.getTopFlag() == 1,
                post.getFeaturedFlag() == 1,
                post.isOffline(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getCollectCount(),
                tags,
                post.getCreateTime(),
                post.getUpdateTime());
    }
}
