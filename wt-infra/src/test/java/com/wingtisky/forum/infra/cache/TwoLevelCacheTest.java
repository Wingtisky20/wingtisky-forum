package com.wingtisky.forum.infra.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TwoLevelCache} 的**纯逻辑**测试：用一个内存 Map 冒充 L2，不连 Redis。
 *
 * <p><b>这里测不到的东西，才是另一半</b>：TTL 到底有没有写进 Redis、抖动有没有生效、
 * 墓碑是不是真的短命——这三件事用假 Redis 证明不了，拿假客户端断言"我传了 30 秒进去"
 * 只是证明参数传对了，不是证明缓存行为对了。它们在
 * {@code app/src/test/.../TwoLevelCacheRedisIntegrationTest} 里对着真实 Redis 测。
 *
 * <p>这条界线是**故意画的**：单测守"分支走对了没有"，集成测试守"行为对不对"。
 */
class TwoLevelCacheTest {

    private static final Duration L1_TTL = Duration.ofSeconds(45);
    private static final Duration L2_TTL = Duration.ofMinutes(5);
    private static final Duration JITTER = Duration.ofSeconds(60);
    private static final Duration NULL_TTL = Duration.ofSeconds(60);
    private static final Duration LOCK_WAIT = Duration.ofMillis(200);

    private static final CachePolicy POLICY =
            new CachePolicy(L1_TTL, L2_TTL, JITTER, NULL_TTL, LOCK_WAIT);

    private static final String KEY = "wt:cache:post:detail:1";

    /** 一个带时间字段的记录，用来确认 JSON 往返不会把 LocalDateTime 弄丢。 */
    record Sample(Long id, String title, List<String> tags, LocalDateTime createTime) {
    }

    private StringRedisTemplate redis;
    private RLock lock;
    private RedissonClient redisson;
    private LocalCache localCache;
    private CacheEvictionBroadcaster broadcaster;
    private TwoLevelCache cache;

    /** 哪些 key 被以什么 TTL 写进了"L2"。 */
    private Map<String, Duration> writtenTtl;
    private Map<String, String> l2;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // 用并发容器：下面有一组**多线程**的用例，用 HashMap 会被并发写坏，
        // 而那种坏是偶发的、看着像业务 bug
        l2 = new ConcurrentHashMap<>();
        writtenTtl = new ConcurrentHashMap<>();

        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(anyString())).thenAnswer(inv -> l2.get(inv.getArgument(0)));
        doAnswer(inv -> {
            String k = inv.getArgument(0);
            l2.put(k, inv.getArgument(1));
            writtenTtl.put(k, inv.getArgument(2));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        doAnswer(inv -> {
            l2.remove(inv.getArgument(0));
            writtenTtl.remove(inv.getArgument(0));
            return Boolean.TRUE;
        }).when(redis).delete(anyString());

        // 默认的锁：**来者不拒，立刻到手**——这样其余用例测的是缓存逻辑本身，
        // 不会被锁的行为干扰。锁自己的用例在下面，会换成别的假锁。
        lock = alwaysGrantedLock();
        redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);

        // 每个用例一份全新的 L1，否则上一个用例留下的条目会渗进来
        localCache = new LocalCache();
        // 广播单独有一整个测试类守着（CacheEvictionBroadcasterTest），
        // 这里只需要它别抛异常——所以用 mock，不重复测它
        broadcaster = mock(CacheEvictionBroadcaster.class);

        cache = newCacheWith(lock);
    }

    @Test
    @DisplayName("未命中 → 回源一次 → 值进两层；再取同样两次，不会再回源")
    void loadsOnceThenServesFromCache() {
        AtomicInteger loads = new AtomicInteger();
        Sample value = sample("第一次");

        Sample first = cache.get(KEY, Sample.class, POLICY, () -> {
            loads.incrementAndGet();
            return value;
        });
        assertThat(first).isEqualTo(value);
        assertThat(loads).hasValue(1);
        assertThat(l2).containsKey(KEY);

        // 第二次：应当由 L1 直接给，连 L2 都不用查
        assertThat(cache.get(KEY, Sample.class, POLICY, this::failIfCalled)).isEqualTo(value);
        assertThat(cache.get(KEY, Sample.class, POLICY, this::failIfCalled)).isEqualTo(value);
        assertThat(loads).hasValue(1);
        assertThat(cache.loadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("L1 空但 L2 有 → 由 L2 供给，且回填进 L1")
    void servesFromL2AndBackfillsL1() {
        // 只往 L2 里放，不让它经过 L1（模拟"另一个节点写进去的"，或本进程刚重启）
        l2.put(KEY, "{\"id\":1,\"title\":\"来自L2\",\"tags\":[],\"createTime\":null}");

        Sample value = cache.get(KEY, Sample.class, POLICY, this::failIfCalled);

        assertThat(value.title()).isEqualTo("来自L2");
        assertThat(cache.loadCount()).isZero();
        // 回填之后，下一次连 L2 都不用查了——把 L2 清掉仍然取得到
        l2.clear();
        assertThat(cache.get(KEY, Sample.class, POLICY, this::failIfCalled).title()).isEqualTo("来自L2");
    }

    @Test
    @DisplayName("【防穿透】回源拿到 null → 写墓碑；再取同一个 key 不会重复回源")
    void writesTombstoneWhenLoaderReturnsNull() {
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get(KEY, Sample.class, POLICY, () -> {
            loads.incrementAndGet();
            return null;
        })).isNull();

        assertThat(l2.get(KEY)).isEqualTo(TwoLevelCache.NULL_SENTINEL);
        assertThat(loads).hasValue(1);

        // 关键的一条：**再取一次不会再打回源**。没有墓碑的话，
        // "反复查一个不存在的 id"就能绕开缓存直接压到数据库上
        assertThat(cache.get(KEY, Sample.class, POLICY, this::failIfCalled)).isNull();
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("【防穿透】墓碑的 TTL 请求成短的那一档，不是正常的 L2 TTL")
    void tombstoneUsesShortTtl() {
        cache.get(KEY, Sample.class, POLICY, () -> null);

        assertThat(writtenTtl.get(KEY))
                .as("墓碑必须用 nullTtl，不能用 l2Ttl——否则新建的对象会被当成不存在")
                .isEqualTo(NULL_TTL);
    }

    @Test
    @DisplayName("evict 之后 → 两层都空了 → 下一次取会重新回源")
    void evictForcesReload() {
        AtomicInteger loads = new AtomicInteger();
        cache.get(KEY, Sample.class, POLICY, () -> {
            loads.incrementAndGet();
            return sample("旧值");
        });

        cache.evict(KEY);

        assertThat(l2).doesNotContainKey(KEY);
        Sample reloaded = cache.get(KEY, Sample.class, POLICY, () -> {
            loads.incrementAndGet();
            return sample("新值");
        });
        assertThat(reloaded.title()).isEqualTo("新值");
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("【防雪崩】连续写入的 key，请求的 TTL 不完全相同（抖动真的在算）")
    void appliesJitterSoKeysDoNotExpireTogether() {
        List<Duration> requested = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String key = "wt:cache:post:detail:" + i;
            cache.get(key, Sample.class, POLICY, () -> sample("v"));
            requested.add(writtenTtl.get(key));
        }

        assertThat(requested).allSatisfy(ttl ->
                assertThat(ttl).isBetween(L2_TTL, L2_TTL.plus(JITTER)));
        assertThat(requested.stream().distinct().count())
                .as("20 次写入的 TTL 如果全都一样，说明抖动没生效——"
                        + "那么这批 key 会在同一刻一起过期，防雪崩就是假的")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("L1 存的是引用而非副本——所以缓存对象必须是不可变的（此处固化这条约定）")
    void l1ReturnsTheSameInstance() {
        Sample value = sample("同一份");
        cache.get(KEY, Sample.class, POLICY, () -> value);

        Sample again = cache.get(KEY, Sample.class, POLICY, this::failIfCalled);

        // 同一个对象引用：这**不是 bug，是设计**。代价是调用方拿到后改它，
        // 缓存里的也跟着变，而且没有任何代码删过这个 key（设计稿 §7）。
        // 所以缓存对象一律用 record + 构造时拷贝集合——这条约定由
        // CachedPostDetail 的测试去守，这里只把"确实是引用"这件事写明白。
        assertThat(again).isSameAs(value);
    }

    @Test
    @DisplayName("L2 里躺着坏数据时：记 WARN、删掉它、退回回源，读**不应该失败**")
    void corruptedL2FallsBackToLoader() {
        l2.put(KEY, "这不是合法的 JSON");

        Sample value = cache.get(KEY, Sample.class, POLICY, () -> sample("回源救回来"));

        assertThat(value.title()).isEqualTo("回源救回来");
        // 坏数据要被清掉，否则每次都要走一遍"解析失败→回源"
        assertThat(l2.get(KEY)).isNotEqualTo("这不是合法的 JSON");
    }

    // ---------- 防击穿（Task 3） ----------

    @Test
    @DisplayName("【防击穿】20 个并发撞上同一个空缓存：回源**恰好 1 次**")
    void concurrentMissesLoadOnlyOnce() throws Exception {
        cache = newCacheWith(serializingLock());

        long loads = hammer(20, () -> sample("唯一的一份"));

        assertThat(loads)
                .as("20 个并发只应回源 1 次——这就是防击穿要的效果")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("【反面】锁失去互斥（人人都能进临界区）：同样 20 个并发，回源**远多于 1 次**")
    void withoutMutualExclusionEveryRequestLoadsItsOwn() throws Exception {
        cache = newCacheWith(alwaysGrantedLock());

        long loads = hammer(20, () -> sample("每人一份"));

        assertThat(loads)
                .as("这一条才是上面那条的证据：只报「回源 1 次」说明不了是锁起的作用，"
                        + "让锁不互斥之后数字必须变差")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("抢不到锁、轮询也等不到别人写 → **降级回源**：拿得到值，不卡死也不报错")
    void degradesToLoadingInsteadOfHanging() {
        cache = newCacheWith(neverGrantedLock());

        Sample value = cache.get(KEY, Sample.class, POLICY, () -> sample("降级拿到的"));

        assertThat(value.title()).isEqualTo("降级拿到的");
        assertThat(cache.loadCount()).as("降级那一次也要算进回源次数").isEqualTo(1);
    }

    @Test
    @DisplayName("【一个反直觉的事实】「抢不到锁」这条路上，还有「轮询 L2」在兜底")
    void waitingForL2AlsoPreventsDuplicateLoads() throws Exception {
        // 这一条是**测试写错之后才发现的**，值得留着：
        //
        // 我原本把"永远抢不到锁"当成防击穿的反面，以为那样 20 个并发会各自回源一次。
        // 实测不是——回源仍然是 1 次。原因是"抢不到锁"之后还有⑤那条路：
        // 等一会儿、轮询 L2，而先拿到锁的那个人会把值写进去。
        //
        // 所以防击穿其实有**两道**：锁（串行化）+ 等锁期间的轮询。
        // 真正的反面必须把两道都拿掉（见上面那条"失去互斥"）。
        cache = newCacheWith(neverGrantedLock());

        long loads = hammer(20, () -> sample("靠轮询拿到的"));

        assertThat(loads)
                .as("抢不到锁时，轮询 L2 仍会把大部分并发请求拦住")
                .isLessThan(20);
    }

    @Test
    @DisplayName("锁键由缓存键推导而来——两者永远一一对应，不会各写各的")
    void lockKeyIsDerivedFromCacheKey() {
        cache.get(KEY, Sample.class, POLICY, () -> sample("v"));

        // KEY = wt:cache:post:detail:1  →  锁键 = wt:lock:cache:post:detail:1
        verify(redisson).getLock("wt:lock:cache:post:detail:1");
    }

    // ---------- 辅助 ----------

    /** 让 N 个线程同时撞同一个 key，返回累计的回源次数。 */
    private long hammer(int threads, Supplier<Sample> answer) throws Exception {
        Sample expected = answer.get();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Sample>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return cache.get(KEY, Sample.class, POLICY, answer);
            }));
        }
        start.countDown();     // 一起放出去，尽量制造同时未命中的场面
        for (Future<Sample> future : futures) {
            assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
        }
        pool.shutdown();
        return cache.loadCount();
    }

    private TwoLevelCache newCacheWith(RLock customLock) {
        // 复用同一个 redisson mock：另建一个的话，"锁键推导"那条用例里的
        // verify(redisson) 验的是另一个对象，会静静地验不到东西
        when(redisson.getLock(anyString())).thenReturn(customLock);
        return new TwoLevelCache(redis, objectMapper(), redisson, localCache, broadcaster);
    }

    private RLock alwaysGrantedLock() {
        RLock granted = mock(RLock.class);
        try {
            when(granted.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(granted.isHeldByCurrentThread()).thenReturn(true);
        return granted;
    }

    private RLock neverGrantedLock() {
        RLock denied = mock(RLock.class);
        try {
            when(denied.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return denied;
    }

    /**
     * 一把**真的会互斥**的假锁：内部用一个公平的 {@link ReentrantLock}。
     * 这是"20 个并发只回源 1 次"那条用例能成立的前提——
     * 用"永远返回 true"的假锁，20 个线程会一起冲进临界区，测出来的就不是防击穿了。
     */
    private RLock serializingLock() {
        ReentrantLock real = new ReentrantLock(true);
        RLock serialized = mock(RLock.class);
        try {
            when(serialized.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                long waitMs = inv.getArgument(0);
                TimeUnit unit = inv.getArgument(1);
                return real.tryLock(waitMs, unit);
            });
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(serialized.isHeldByCurrentThread()).thenAnswer(inv -> real.isHeldByCurrentThread());
        doAnswer(inv -> {
            if (real.isHeldByCurrentThread()) {
                real.unlock();
            }
            return null;
        }).when(serialized).unlock();
        return serialized;
    }

    private Sample sample(String title) {
        return new Sample(1L, title, List.of("Redis", "Caffeine"), LocalDateTime.of(2026, 10, 6, 14, 0));
    }

    private Sample failIfCalled() {
        throw new AssertionError("这次取值不该回源——缓存本应命中");
    }

    /**
     * 与 Spring Boot 自动装配出来的那一个保持同样的关键设置——
     * 尤其是关掉"日期写成时间戳"。生产上用注入进来的那个 bean，
     * 这里只是让单测里的 JSON 形状与生产一致。
     */
    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
