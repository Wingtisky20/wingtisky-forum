package com.wingtisky.forum.infra.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wingtisky.forum.infra.redis.RedisKey;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 两级缓存的**机制**：查 L1（进程内）→ 查 L2（Redis）→ 都没有就调 {@code loader} 回源，
 * 再把结果写回两层。
 *
 * <p><b>它属于"机制"不是"策略"</b>（architecture.md §4.3）：它回答的是
 * "怎么安全地取到一个值"，不回答"缓存什么、存多久"——
 * 后者由调用方通过 {@link CachePolicy} 和一个回源函数传进来。
 * 换成 M7 的商品详情，这个类一行都不用改。
 *
 * <p><b>防击穿已经做在里面了</b>（回源那一步用 Redisson 锁串起来，锁内再 double-check）：
 * 热点 key 刚过期的一瞬间，只有抢到锁的那个请求会去回源，其余的人等它。
 *
 * <p><b>三件事都齐了</b>：防击穿（下面第 ③ 步的锁）、防穿透（第 ① 步的墓碑）、
 * 以及跨节点失效（{@link #evict} 里的广播）。L1 由 {@link LocalCache} 持有——
 * 它被提出来单独做一个组件，正是因为广播要在"收到消息时"清本进程的 L1，
 * 而那一刻手里并没有哪个具体的缓存对象。
 *
 * <h3>两个不显眼但必须做对的地方</h3>
 *
 * <p><b>一、回源返回 null 时要写"墓碑"。</b> 缓存里"没有这个 key"和"这个 key 的值是空"
 * 必须分得开——分不开的话，每次查一个不存在的 id 都会回源，
 * 缓存等于对"查不存在的东西"这类攻击（缓存穿透）完全无效。
 *
 * <p><b>二、墓碑的存活时间必须短</b>，由 {@link CachePolicy#nullTtl()} 单独给。
 * 用正常 TTL 的话，一个新对象建出来之后，它的 id 在墓碑过期前都会被当成"不存在"。
 *
 * <p>读取端的字节序列化用 JSON 而不是 JDK 序列化：**JDK 序列化在 Redis 里是一坨二进制，
 * 出问题时肉眼看不见内容**。这个取舍见 docs/03-design/m3-cache.md §6.1。
 */
@Component
public class TwoLevelCache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    /**
     * L2（Redis）里表示"这个 key 确实不存在"的哨兵值。
     *
     * <p>为什么不用真正的空值：Redis 里"这个 key 不存在"（GET 返回 null）和
     * "这个 key 的值是空字符串"是两件事，而 JSON 序列化很容易把两者弄混。
     * 用一个**看得见**的哨兵，比让它们在反序列化时靠约定区分要稳。
     *
     * <p>为什么是这串字符而不是一个包装类：**在 RedisInsight 里一眼能认出来**。
     * 包装类在可视化工具里只是一坨看不出所以然的 JSON。
     */
    static final String NULL_SENTINEL = "__NULL__";

    /** L1 里表示同样的意思——Caffeine 不接受 null 作为值。 */
    private static final Object NULL_HOLDER = new Object();

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RedissonClient redisson;
    private final LocalCache localCache;
    private final CacheEvictionBroadcaster broadcaster;

    /**
     * 回源次数。**存在的唯一目的是让「100 并发只有 1 次回源」这条闸门可被证伪** ——
     * 没有它，那条闸门只能靠"感觉上应该是 1 次"来宣称（见设计 §9.2）。
     */
    private final AtomicLong loadCount = new AtomicLong();

    /**
     * 必须显式标 {@code @Autowired}：下面还有一个给测试用的构造器，
     * 一个类有**两个**构造器时 Spring 不再猜，会直接去找无参构造器然后报
     * "No default constructor found"。这个错只在**启动**时才炸，单测发现不了
     * ——因为它压根不起来 Spring 上下文（这条是 2026-10-06 实测踩到的）。
     */
    @Autowired
    public TwoLevelCache(StringRedisTemplate redis, ObjectMapper json, RedissonClient redisson,
                         LocalCache localCache, CacheEvictionBroadcaster broadcaster) {
        this.redis = redis;
        this.json = json;
        this.redisson = redisson;
        this.localCache = localCache;
        this.broadcaster = broadcaster;
    }

    /**
     * 取一个值：两级缓存都查过仍没有，才回源。
     *
     * @param key    缓存的键（含命名空间，由调用方按 RedisKey 的规范生成）
     * @param type   值的类型。<b>这个参数不能省</b>——值在 Redis 里是 JSON 字符串，
     *               还原成对象时必须知道还原成什么；而 {@code loader} 只在回源时用到，
     *               靠它推断不出类型（Java 的泛型在运行时被擦掉了）
     * @param policy 存活策略
     * @param loader 回源函数。返回 {@code null} 表示"这个 key 确实不存在"，
     *               会被写成一枚短命的墓碑
     * @return 取到的值；{@code loader} 返回 null 时本方法也返回 {@code null}
     */
    public <V> V get(String key, Class<V> type, CachePolicy policy, Supplier<V> loader) {
        // ① L1：进程内，最快的一档
        Object local = localCache.get(key);
        if (local != null) {
            return local == NULL_HOLDER ? null : type.cast(local);
        }

        // ② L2：Redis
        L2Hit<V> hit = readL2(key, type, policy);
        if (hit != null) {
            return hit.value();
        }

        // ③ 要回源了。先抢锁——**这一步就是防击穿**。
        //    没有它，热点 key 刚过期的那一瞬间，所有并发请求会同时发现"缓存没了"，
        //    然后一起冲到数据库上。缓存在最需要它的时候反而失效。
        RLock lock = redisson.getLock(RedisKey.cacheLock(key));
        if (tryLock(lock, key, policy)) {
            try {
                // ④ double-check：**这一步不能省。**
                //    在你等锁的这段时间里，前一个拿到锁的线程很可能已经把值写进去了。
                //    不重查的话，所有排队的请求会**依次各回源一次**——锁等于白加。
                L2Hit<V> again = readL2(key, type, policy);
                if (again != null) {
                    return again.value();
                }
                return loadAndFill(key, policy, loader);
            } finally {
                unlock(lock, key);
            }
        }

        // ⑤ 没抢到锁：等一会儿，看拿到锁的那个人有没有把值写进去
        L2Hit<V> waited = waitForL2(key, type, policy);
        if (waited != null) {
            return waited.value();
        }

        // ⑥ 等不到就**降级回源**。宁可多查一次库，也不能把请求卡死——
        //    这里若改成抛错或返回空，一次偶发的锁竞争就会变成用户可见的故障。
        return loadAndFill(key, policy, loader);
    }

    /**
     * 让一个 key 失效：清掉本机的 L1 与 L2。
     *
     * <p><b>此刻还不广播</b>——其它节点的 L1 要等 Task 4 的发布订阅。
     * 在那之前，其它节点靠自己的 L1 过期时间兜底（这也是 L1 的 TTL 设短的原因之一）。
     */
    public void evict(String key) {
        localCache.invalidate(key);
        redis.delete(key);
        // 广播给**别的节点**，让它们也清掉自己那份 L1。
        // 少了这一步，多节点部署下别的节点会一直拿旧值供数，直到自己的 L1 过期。
        broadcaster.broadcast(key);
    }

    /** 累计回源次数。给闸门实验用（见类注释）。 */
    public long loadCount() {
        return loadCount.get();
    }

    /** 把回源计数清零。实验要先清零再打流量，否则读数里混着之前跑的量。 */
    public void resetLoadCount() {
        loadCount.set(0L);
    }

    // ---------- 内部 ----------

    /**
     * 查 L2 的结果。用一个类型把两种"没有值"分开——
     * 返回 {@code null} 是"L2 里没有这个键"，返回 {@code L2Hit} 且其 value 为
     * {@code null} 是"L2 里有一枚墓碑"（即：这个对象确实不存在）。
     * 两者后续的处理完全不同（前者要回源，后者要直接对外说"没有"）。
     */
    private record L2Hit<V>(V value) {
    }

    @Nullable
    private <V> L2Hit<V> readL2(String key, Class<V> type, CachePolicy policy) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        if (NULL_SENTINEL.equals(raw)) {
            putL1(key, NULL_HOLDER, policy.nullTtl());
            return new L2Hit<>(null);
        }
        V value = deserialize(key, raw, type);
        if (value == null) {
            // 反序列化失败时 deserialize 已经记过日志并清掉了这个键，
            // 这里当作"没查到"，顺着往下走去回源——**缓存坏了不该让读失败**
            return null;
        }
        putL1(key, value, policy.l1Ttl());
        return new L2Hit<>(value);
    }

    /** 真正的回源：调业务给的函数，再把结果写回两层。调用方负责持锁。 */
    private <V> V loadAndFill(String key, CachePolicy policy, Supplier<V> loader) {
        loadCount.incrementAndGet();
        V loaded = loader.get();
        if (loaded == null) {
            redis.opsForValue().set(key, NULL_SENTINEL, policy.nullTtl());
            putL1(key, NULL_HOLDER, policy.nullTtl());
        } else {
            redis.opsForValue().set(key, serialize(loaded), policy.nextL2Ttl());
            putL1(key, loaded, policy.l1Ttl());
        }
        return loaded;
    }

    private boolean tryLock(RLock lock, String key, CachePolicy policy) {
        try {
            return lock.tryLock(policy.lockWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 被中断时**必须把中断标志还回去**，否则上层再也感知不到"该停了"。
            // 这在 Tomcat 关停或请求超时时是真的会发生，吞掉它会让线程停不下来。
            Thread.currentThread().interrupt();
            log.warn("等待缓存回源锁时被中断，改为直接回源：[{}]", key);
            return false;
        }
    }

    private void unlock(RLock lock, String key) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            // 解锁失败**不该把已经拿到的值丢掉**——调用方要的是值，不是锁的状态。
            // 何况 Redisson 的锁自带租期，异常情况下也会自己释放。
            log.warn("释放缓存回源锁失败，交由租期自动释放：[{}]", key, e);
        }
    }

    /** 没抢到锁时，按 {@code lockWait} 分出的小段轮询 L2，等别人把值写进去。 */
    @Nullable
    private <V> L2Hit<V> waitForL2(String key, Class<V> type, CachePolicy policy) {
        long waitMs = policy.lockWait().toMillis();
        if (waitMs <= 0) {
            return null;
        }
        long intervalMs = Math.max(10L, waitMs / 10);
        long deadline = System.nanoTime() + policy.lockWait().toNanos();
        try {
            while (System.nanoTime() < deadline) {
                Thread.sleep(intervalMs);
                L2Hit<V> hit = readL2(key, type, policy);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void putL1(String key, Object value, Duration ttl) {
        localCache.putWithTtl(key, value, ttl);
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 序列化失败是**代码问题**（比如类型里有个 Jackson 不认识的字段），
            // 不该把它悄悄变成"缓存没命中"——那会让这个 bug 永远发现不了。
            throw new IllegalStateException("缓存值无法序列化为 JSON：" + value.getClass(), e);
        }
    }

    private <V> V deserialize(String key, String raw, Class<V> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException e) {
            // 反序列化失败多半是**环境问题**（缓存里躺着旧版本写的数据、或被手工改过），
            // 处理方式与上面相反：记一条 WARN、删掉这个坏 key、退回回源。
            // 判断依据是"这次的失败该由谁负责"——写入方的问题不该让读取方 500。
            log.warn("缓存值无法反序列化为 {}，已删除该 key 并回源：[{}]", type.getSimpleName(), key, e);
            redis.delete(key);
            return null;
        }
    }
}
