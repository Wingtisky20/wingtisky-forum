package com.wingtisky.forum;

import com.wingtisky.forum.infra.cache.CachePolicy;
import com.wingtisky.forum.infra.cache.TwoLevelCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TwoLevelCache} 对着**真实 Redis** 的那一半：TTL 有没有真的写进去、
 * 抖动有没有真的生效、墓碑是不是真的短命。
 *
 * <p><b>为什么这些不能在单测里做</b>：单测用的是假 Redis，只能断言"我传了 30 秒进去"。
 * 而这里要问的是另一件事——**Redis 里那个键的剩余存活时间到底是多少**。
 * 前者证明参数传对了，后者才证明缓存行为对了。比如"按条目设置过期时间"这件事，
 * Caffeine 那一侧要配置对（{@code expireAfter(Expiry)} + {@code expireVariably()}），
 * 配错了不会报错，只会**默默用兜底时长**——假 Redis 永远发现不了。
 *
 * <p>这也正是 {@code @Tag("integration")} 的意义：这类事在 CI 上证明不了（ADR-0007）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class TwoLevelCacheRedisIntegrationTest {

    /**
     * 故意取小：测试要等 TTL 真的流逝，取值太大就跑得慢。
     * 比例仍按生产的样子来——L1 短、L2 长、抖动量级可见、墓碑最短。
     */
    private static final Duration L1_TTL = Duration.ofSeconds(30);
    private static final Duration L2_TTL = Duration.ofSeconds(100);
    private static final Duration JITTER = Duration.ofSeconds(30);
    private static final Duration NULL_TTL = Duration.ofSeconds(10);
    private static final Duration LOCK_WAIT = Duration.ofMillis(200);

    private static final CachePolicy POLICY =
            new CachePolicy(L1_TTL, L2_TTL, JITTER, NULL_TTL, LOCK_WAIT);

    private static final String PREFIX = "wt:cache:post:detail:it:";

    @Autowired
    private TwoLevelCache cache;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("值以 JSON 明文存进 Redis——别的客户端与可视化工具都读得到")
    void storesPlainJson() {
        cache.get(PREFIX + "json", String.class, POLICY, () -> "hello");

        // 直接用 StringRedisTemplate 读：能读到，说明存的**不是** JDK 序列化的二进制，
        // 也不是 Redisson 那种 Kryo 格式（那个坑在 RedissonSmokeIntegrationTest 里记着）
        assertThat(redis.opsForValue().get(PREFIX + "json")).isEqualTo("\"hello\"");
    }

    @Test
    @DisplayName("正常值的 TTL 落在 [l2Ttl, l2Ttl + jitter] 区间内")
    void normalTtlWithinPolicyRange() {
        cache.get(PREFIX + "normal", String.class, POLICY, () -> "v");

        Long ttl = redis.getExpire(PREFIX + "normal", TimeUnit.SECONDS);

        assertThat(ttl).isNotNull();
        // 允许 2 秒误差：Redis 的 TTL 按秒向下取整，而我们的基准也按秒算
        assertThat(ttl).isBetween(L2_TTL.toSeconds() - 2, L2_TTL.toSeconds() + JITTER.toSeconds());
    }

    @Test
    @DisplayName("【防雪崩】连写 20 个键，它们**真实的** TTL 不完全相同")
    void jitterIsRealInRedis() {
        List<Long> ttls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String key = PREFIX + "jitter:" + i;
            cache.get(key, String.class, POLICY, () -> "v");
            ttls.add(redis.getExpire(key, TimeUnit.SECONDS));
        }

        assertThat(ttls).allSatisfy(ttl -> assertThat(ttl)
                .isBetween(L2_TTL.toSeconds() - 2, L2_TTL.toSeconds() + JITTER.toSeconds()));
        assertThat(ttls.stream().distinct().count())
                .as("20 个键的 TTL 全一样，说明写入时没加抖动——"
                        + "它们会在同一刻集体过期，所有请求一起回源（雪崩）")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("【防穿透】墓碑真实的 TTL 明显短于正常值")
    void tombstoneTtlIsShorterInRedis() {
        cache.get(PREFIX + "missing", String.class, POLICY, () -> null);

        Long ttl = redis.getExpire(PREFIX + "missing", TimeUnit.SECONDS);

        assertThat(redis.opsForValue().get(PREFIX + "missing")).isEqualTo("__NULL__");
        assertThat(ttl).isNotNull();
        assertThat(ttl)
                .as("墓碑必须用 nullTtl（%s 秒）而不是 l2Ttl（%s 秒）——"
                                + "墓碑留久了，新建的对象会被当成不存在",
                        NULL_TTL.toSeconds(), L2_TTL.toSeconds())
                .isBetween(1L, NULL_TTL.toSeconds() + 2);
    }

    @Test
    @DisplayName("【防击穿·真 Redisson 锁】20 个并发撞同一个空缓存：回源**恰好 1 次**")
    void realRedissonLockSerializesLoads() throws Exception {
        // 单测里那把锁是假的；这一条用的是**真的 RedissonClient + 真的 Redis**——
        // 它同时验证了两件事：锁真的能互斥，以及 waitForL2 那条路真的接得上。
        cache.resetLoadCount();
        String key = PREFIX + "lock";

        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return cache.get(key, String.class, POLICY, () -> "唯一的一份");
            }));
        }
        start.countDown();

        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("唯一的一份");
        }
        pool.shutdown();

        assertThat(cache.loadCount())
                .as("20 个并发只应回源 1 次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("L1 的过期时间按条目设置，而非退化成兜底的 60 秒")
    void l1TtlIsAppliedPerEntry() throws InterruptedException {
        // L1 的 TTL 是 30 秒。这条测试给一个 **5 秒**的 L1 TTL，
        // 然后等 6 秒——若 L1 真的按条目过期，此刻应当已经回源。
        //
        // 这条是在防一个不会报错的错误：Caffeine 的可变过期要求缓存用
        // expireAfter(Expiry) 构建，配错时 expireVariably() 拿不到，
        // 代码会**静静退化成兜底时长**（60 秒）。那时 L1 就成了"消息丢了也不失效"
        // 的脏数据来源，而表面上一切正常。
        CachePolicy shortL1 = new CachePolicy(
                Duration.ofSeconds(5), Duration.ofSeconds(100), Duration.ZERO,
                Duration.ofSeconds(5), Duration.ofMillis(200));
        String key = PREFIX + "l1ttl";

        cache.get(key, String.class, shortL1, () -> "第一版");

        // 关键的一步：**只清掉 L2**，把 L1 单独隔出来。
        // 不清的话，等 L1 过期后会由 L2 供数，返回的仍是"第一版"——
        // 于是"L1 有没有按条目过期"这件事根本观察不到（第一版就是这么写错的）。
        redis.delete(key);

        assertThat(cache.get(key, String.class, shortL1, () -> {
            throw new AssertionError("L2 已清空，这一次应当由 L1 供数、不该回源");
        })).as("先确认 L1 里确实有值，否则下面的过期断言毫无意义")
                .isEqualTo("第一版");

        Thread.sleep(6_000);

        assertThat(cache.get(key, String.class, shortL1, () -> "第二版"))
                .as("L1 应当已按 5 秒的条目 TTL 过期——若这里仍是「第一版」，"
                        + "说明按条目设 TTL 没生效、退回了兜底时长（默认 60 秒）")
                .isEqualTo("第二版");
    }
}
