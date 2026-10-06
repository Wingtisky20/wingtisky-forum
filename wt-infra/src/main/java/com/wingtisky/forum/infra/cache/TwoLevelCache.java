package com.wingtisky.forum.infra.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
 * <p><b>它现在还不做两件事</b>（后面两个 Task 各自加，一次只加一件）：
 * <ul>
 *   <li><b>防击穿</b>（Redisson 锁 + 锁内 double-check）—— Task 3</li>
 *   <li><b>跨节点失效广播</b>（发布订阅清其它节点的 L1）—— Task 4</li>
 * </ul>
 * 所以此刻的 {@link #evict} 只清本机的两层。**这不是遗漏，是刻意的施工顺序**：
 * 一次只引入一个会失效的机制，坏了好定位。
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

    /** L1 的条目上限。它只是防"key 基数失控"把内存吃掉，不是淘汰策略的核心。 */
    private static final long L1_MAX_ENTRIES = 10_000L;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Cache<String, Object> l1;

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
    public TwoLevelCache(StringRedisTemplate redis, ObjectMapper json) {
        this(redis, json, defaultL1());
    }

    /** 供测试注入一个可控的 L1（比如换成很小的上限，或读它的命中统计）。 */
    TwoLevelCache(StringRedisTemplate redis, ObjectMapper json, Cache<String, Object> l1) {
        this.redis = redis;
        this.json = json;
        this.l1 = l1;
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
        Object local = l1.getIfPresent(key);
        if (local != null) {
            return local == NULL_HOLDER ? null : type.cast(local);
        }

        // ② L2：Redis
        String raw = redis.opsForValue().get(key);
        if (raw != null) {
            if (NULL_SENTINEL.equals(raw)) {
                putL1(key, NULL_HOLDER, policy.nullTtl());
                return null;
            }
            V fromRedis = deserialize(key, raw, type);
            if (fromRedis != null) {
                putL1(key, fromRedis, policy.l1Ttl());
                return fromRedis;
            }
            // 反序列化失败已经在 deserialize 里记过日志并清掉了这个 key，
            // 这里顺着往下走去回源——**缓存坏了不该让读失败**。
        }

        // ③ 回源，然后写回两层
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

    /**
     * 让一个 key 失效：清掉本机的 L1 与 L2。
     *
     * <p><b>此刻还不广播</b>——其它节点的 L1 要等 Task 4 的发布订阅。
     * 在那之前，其它节点靠自己的 L1 过期时间兜底（这也是 L1 的 TTL 设短的原因之一）。
     */
    public void evict(String key) {
        l1.invalidate(key);
        redis.delete(key);
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

    private void putL1(String key, Object value, java.time.Duration ttl) {
        // 用可变过期时间：L1 的 TTL 与 L2 不同档（前者短得多，是发布订阅丢消息时的兜底）。
        // 若哪天这个缓存不是用 expireAfter(Expiry) 建的，这里会拿不到 expireVariably——
        // 那就退回普通 put（靠 defaultL1 里的兜底时长），而不是让调用方挂在异常上。
        var variably = l1.policy().expireVariably();
        if (variably.isPresent()) {
            variably.get().put(key, value, ttl);
        } else {
            log.warn("L1 未启用可变过期，[{}] 退化为默认 TTL", key);
            l1.put(key, value);
        }
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

    private static Cache<String, Object> defaultL1() {
        return Caffeine.newBuilder()
                .maximumSize(L1_MAX_ENTRIES)
                // recordStats()：闸门实验要读命中率，没有它就只剩"我觉得应该挺高的"
                .recordStats()
                .expireAfter(new Expiry<String, Object>() {
                    @Override
                    public long expireAfterCreate(String key, Object value, long currentTime) {
                        // 实际写入都会走 putL1 的 expireVariably().put(key, value, ttl)，
                        // 那个时长会覆盖这里；这个返回值只是"忘了指定时"的兜底。
                        return java.time.Duration.ofSeconds(60).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Object value,
                                                  long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, Object value,
                                                long currentTime, long currentDuration) {
                        // 读不续期：访问频繁的 key 一直不回源看起来很划算，
                        // 但那样它永远不会去拿新数据，等于把缓存变成了事实上的权威副本。
                        return currentDuration;
                    }
                })
                .build();
    }
}
