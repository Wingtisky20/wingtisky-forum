package com.wingtisky.forum.forum.content.cache;

import com.wingtisky.forum.infra.cache.CachePolicy;
import com.wingtisky.forum.infra.cache.TwoLevelCache;
import com.wingtisky.forum.infra.redis.RedisKey;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    /**
     * 存活策略。**Task 9 会把它挪进 `application.yml`**（设计稿 §8）。
     *
     * <p>现在先写成常量：它们是**初始值**，M10 压测时才校准；
     * 但把它提前挪进配置会让 Task 9 无事可做，而配置项与开关是一整套东西
     * （总开关、各档时长、回写间隔），一次做完才好验证"开关真的能关掉缓存"。
     *
     * <p>五档各为什么是这个量级，见 {@link CachePolicy} 的类注释。
     */
    private static final CachePolicy POLICY = new CachePolicy(
            Duration.ofSeconds(45),     // L1：短——它是发布订阅丢消息时的兜底
            Duration.ofMinutes(5),      // L2 基准
            Duration.ofSeconds(60),     // 抖动：防雪崩
            Duration.ofSeconds(60),     // 墓碑：必须短（"不存在"随时可能变成"存在"）
            Duration.ofMillis(500));    // 抢不到回源锁最多等 500ms

    private final TwoLevelCache cache;

    public PostDetailCache(TwoLevelCache cache) {
        this.cache = cache;
    }

    /**
     * 取一条帖子的缓存部分。
     *
     * @param loader 回源函数。**返回 {@code null} 表示这条帖子不存在**
     *               （或已删/已下架到看不成的程度）——那时会写一枚短命的墓碑，
     *               避免"反复查一个不存在的 id"直接压到数据库上
     */
    @Nullable
    public CachedPostDetail get(Long postId, Supplier<CachedPostDetail> loader) {
        return cache.get(RedisKey.cachePostDetail(postId), CachedPostDetail.class, POLICY, loader);
    }

    /**
     * 让某条帖子的缓存失效。
     *
     * <p><b>凡是能改变 {@link CachedPostDetail} 里任何一个字段的写操作，都必须调它</b>
     * ——编辑、删除、治理（置顶/加精/下架）、点赞、收藏、评论。
     * 完整的一张表在设计稿 §3.3；Task 7 逐条接上。
     */
    public void evict(Long postId) {
        cache.evict(RedisKey.cachePostDetail(postId));
    }

    /** 当前策略（测试与观测用）。 */
    public static CachePolicy policy() {
        return POLICY;
    }
}
