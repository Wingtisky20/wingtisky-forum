package com.wingtisky.forum.infra.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 本进程的 L1：**一个进程一个实例**，所有缓存共用它。
 *
 * <p><b>为什么它要从 {@code TwoLevelCache} 里独立出来</b>：跨节点失效那条路要能
 * "清掉本进程的 L1"，而清的动作发生在**收到广播消息的时候**——
 * 那时手里没有哪个具体的缓存对象。如果 L1 还是 {@code TwoLevelCache} 的私有字段，
 * 广播就得去遍历"本进程有哪些缓存"，而那是个注册表、是个新概念。
 * 提出来之后，"清本机 L1"就是一句 `localCache.invalidate(key)`。
 *
 * <p><b>为什么所有缓存共用一个实例、而不是每个缓存一个</b>：代价是命中率统计
 * 是"合起来"的一个数。M3 只有帖子详情一种缓存，两者等价；
 * 将来真需要分缓存看命中率，再按缓存名拆开（那时有两个真实样本，拆的方向才对）。
 *
 * <p>它只管**存放**：键是什么、值是什么、活多久，都由调用方给。
 * 类型安全不在这一层——L1 里存的是 Object，
 * 而"这个键上应该是什么类型"是 {@code TwoLevelCache} 的事。
 */
@Component
public class LocalCache {

    private static final Logger log = LoggerFactory.getLogger(LocalCache.class);

    /** 条目上限。它只是防"键基数失控"把内存吃掉，不是淘汰策略的核心。 */
    private static final long MAX_ENTRIES = 10_000L;

    /** 忘了指定 TTL 时的兜底时长。正常路径都走 {@link #putWithTtl}。 */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final Cache<String, Object> caffeine;

    public LocalCache() {
        this.caffeine = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                // recordStats()：闸门实验要读命中率。
                // 没有它，"命中率"就只剩"我觉得应该挺高的"
                .recordStats()
                .expireAfter(new Expiry<String, Object>() {
                    @Override
                    public long expireAfterCreate(String key, Object value, long currentTime) {
                        // 实际写入都走 putWithTtl，那个时长会覆盖这里；
                        // 这个返回值只是"忘了指定时"的兜底
                        return DEFAULT_TTL.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Object value,
                                                  long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, Object value,
                                                long currentTime, long currentDuration) {
                        // **读不续期**：访问频繁的 key 一直不回源看起来很划算，
                        // 但那样它永远不会去拿新数据，等于把缓存变成了事实上的权威副本。
                        // 跨节点失效本来就不太可靠（发布订阅会丢消息），
                        // 再叠一个"读就续期"，脏数据就可能永远留在那里。
                        return currentDuration;
                    }
                })
                .build();
    }

    /**
     * 取一个条目。
     *
     * @return {@code null} 表示这个键上没有东西；否则是当初存进去的值
     *         （注意：可能是一个表示"值就是空"的哨兵对象，由调用方约定）
     */
    @Nullable
    public Object get(String key) {
        return caffeine.getIfPresent(key);
    }

    /** 存一个条目，并按条目指定过期时间。 */
    public void putWithTtl(String key, Object value, Duration ttl) {
        // 用可变过期时间：L1 的 TTL 与 L2 不同档（前者短得多，是发布订阅丢消息时的兜底）。
        // 若哪天这个缓存不是用 expireAfter(Expiry) 建的，这里会拿不到 expireVariably——
        // 那就退回普通 put（靠 DEFAULT_TTL），而不是让调用方挂在异常上。
        var variably = caffeine.policy().expireVariably();
        if (variably.isPresent()) {
            variably.get().put(key, value, ttl);
        } else {
            log.warn("L1 未启用可变过期，[{}] 退化为默认 TTL", key);
            caffeine.put(key, value);
        }
    }

    /** 让一个键失效。跨节点失效收到广播时，清的就是这里。 */
    public void invalidate(String key) {
        caffeine.invalidate(key);
    }

    /** 命中率等统计（闸门实验用）。 */
    public CacheStats stats() {
        return caffeine.stats();
    }

    /** 当前条目数（测试与观测用）。 */
    public long estimatedSize() {
        return caffeine.estimatedSize();
    }
}
