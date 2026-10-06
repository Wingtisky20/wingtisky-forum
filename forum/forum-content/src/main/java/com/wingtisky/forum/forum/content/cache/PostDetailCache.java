package com.wingtisky.forum.forum.content.cache;

import com.wingtisky.forum.infra.cache.TwoLevelCache;
import com.wingtisky.forum.infra.redis.RedisKey;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 帖子详情的缓存**策略**：存什么（{@link CachedPostDetail}）、存多久、键怎么生成。
 *
 * <p><b>它和 {@code TwoLevelCache} 的分工是 architecture.md §4.3 那条边界线的实例</b>：
 * <ul>
 *   <li>{@code TwoLevelCache}（在 {@code wt-infra}）是**机制**——"怎么安全地取到一个值"，
 *       它不知道帖子的存在；</li>
 *   <li>本类（在 {@code forum-content}）是**策略**——"缓存哪些数据、存多久、
 *       哪些写操作该删它"。换成 M7 的商品详情，改的是这一类，不动那一类。</li>
 * </ul>
 *
 * <p><b>本类刻意只做"取"与"删"</b>，不做：
 * <ul>
 *   <li><b>组装完整的 {@code PostDetail}</b>（拼上浏览数与"我点过没"）——
 *       那是 Task 6 在 {@code PostService.getDetail} 里做的事。缓存这一层
 *       只认"公共内容"那一半，用户态不该流进来。</li>
 *   <li><b>决定哪些写操作该调 {@link #evict}</b> —— 那是 Task 7 的失效清单，
 *       由各个业务方法自己调。集中在这里会变成"缓存类反过来要懂所有业务"。</li>
 * </ul>
 */
@Component
public class PostDetailCache {

    private final TwoLevelCache cache;
    private final CacheProperties properties;

    public PostDetailCache(TwoLevelCache cache, CacheProperties properties) {
        this.cache = cache;
        this.properties = properties;
    }

    /**
     * 取一条帖子的缓存部分。
     *
     * <p><b>总开关关掉时直接回源、一个 Redis key 都不写。</b> 那是闸门实验里的
     * 「纯 MySQL」那一组：两组必须只差这一个变量，否则比出来的差值里混着别的东西，
     * 说明不了缓存的作用——**而实验结论看起来会很像真的**。
     *
     * @param loader 回源函数。**返回 {@code null} 表示这条帖子不存在**
     *               （或已删/已下架到看不成的程度）——那时会写一枚短命的墓碑，
     *               避免"反复查一个不存在的 id"直接压到数据库上
     */
    @Nullable
    public CachedPostDetail get(Long postId, Supplier<CachedPostDetail> loader) {
        CacheProperties.PostDetail config = properties.postDetail();
        if (!config.enabled()) {
            return loader.get();
        }
        return cache.get(RedisKey.cachePostDetail(postId), CachedPostDetail.class,
                config.toPolicy(), loader);
    }

    /**
     * 让某条帖子的缓存失效。
     *
     * <p><b>凡是能改变 {@link CachedPostDetail} 里任何一个字段的写操作，都必须调它</b>
     * ——编辑、删除、治理（置顶/加精/下架）、点赞、收藏、评论。
     * 完整的一张表在设计稿 §3.3。
     *
     * <p><b>写路径请用 {@link #evictAfterCommit}，不要用这个。</b> 本方法立刻删，
     * 用在事务里等于把顺序做成"先删缓存、再更新库"（理由见
     * {@code TwoLevelCache.evictAfterCommit}）。它留给"本来就不在事务里"的场景。
     */
    public void evict(Long postId) {
        if (!properties.postDetail().enabled()) {
            return;
        }
        cache.evict(RedisKey.cachePostDetail(postId));
    }

    /**
     * 让某条帖子的缓存失效，**等当前事务提交之后**再动手。**写路径一律用它。**
     *
     * <p>为什么必须等提交、回滚时为什么不删、不在事务里时为什么立刻删——
     * 三条理由都写在 {@code TwoLevelCache.evictAfterCommit} 上，这里不重复。
     */
    public void evictAfterCommit(Long postId) {
        if (!properties.postDetail().enabled()) {
            // 开关关掉 = 缓存这一层等于不存在：读不写、写也不删。
            // 每次写仍去 Redis 跑一趟 DEL + 一次广播，是白跑的网络往返
            return;
        }
        cache.evictAfterCommit(RedisKey.cachePostDetail(postId));
    }
}
