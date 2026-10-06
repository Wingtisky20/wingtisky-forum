package com.wingtisky.forum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wingtisky.forum.forum.content.cache.CacheProperties;
import com.wingtisky.forum.forum.content.cache.CachedPostDetail;
import com.wingtisky.forum.infra.cache.CacheEvictionBroadcaster;
import com.wingtisky.forum.infra.cache.LocalCache;
import com.wingtisky.forum.infra.cache.TwoLevelCache;
import com.wingtisky.forum.infra.redis.RedisKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闸门实验 · <b>100 并发只有 1 次回源</b>（spec §6.2，设计 §9.2）。
 *
 * <p>这是本设计的**证据核心**，因为它带一条反面对照：
 * <ul>
 *   <li><b>正面</b>：清空两级缓存 → 100 个线程同时请求同一帖 → 全部 200、回源**恰好 1 次**</li>
 *   <li><b>反面</b>：让锁**失去互斥**（锁对象还在、但人人都能进临界区）→ 同样 100 个并发，
 *       回源**远多于 1 次**</li>
 * </ul>
 *
 * <p><b>为什么反面不能写成"把锁拿掉（永远抢不到）"</b>：那样做实测下来回源**仍然是 1 次**
 * ——因为抢不到锁之后还有第 ⑤ 条路（等一会儿、轮询 L2），而先拿到锁的那个人会把值写进去。
 * 防击穿其实有**两道**：锁 + 等锁期间的轮询。要证伪锁的作用，必须让**互斥**失效，
 * 而不是让"拿到锁"失败。（这条在 {@code TwoLevelCacheTest} 里也踩过一次，见那里的注释。）
 *
 * <p><b>反面只能在机制层做</b>：HTTP 那条路上的锁是启动时装配进去的，
 * 运行时换不掉。所以正面走真实 HTTP、反面直接对着一个"假锁"的 {@code TwoLevelCache}
 * 打——两者问的是同一个问题："回源次数是不是被那把锁压到了 1"。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 100 个并发正好压在读接口 100 次/分钟的额度上——不关限流的话这条测试
        // 是"刚好没超"过去的，多打几次就会变成一片 429（那与防击穿毫无关系）
        properties = "wt.rate-limit.enabled=false")
@ActiveProfiles("test")
@Tag("integration")
class PostDetailCacheConcurrencyTest extends DetailReadBenchmarkSupport {

    private static final int THREADS = 100;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CacheEvictionBroadcaster broadcaster;

    @Autowired
    private CacheProperties cacheProperties;

    @Test
    @DisplayName("★ 100 个并发同时请求同一帖：全部 200，回源**恰好 1 次**")
    void hundredConcurrentRequestsLoadOnlyOnce() throws Exception {
        Long id = hotIds.get(0);
        clearBothLevels(id);
        cache.resetLoadCount();
        cache.resetL2HitCount();

        List<Integer> statuses = hammerHttp(id);

        assertThat(statuses).as("100 个并发都要拿到 200，一个都不能失败").allMatch(s -> s == 200);
        assertThat(cache.loadCount())
                .as("这就是防击穿要的效果：100 个请求同时撞上冷缓存，只有抢到锁的那个去回源")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("【反面】让锁失去互斥（人人都能进临界区）：同样 100 并发，回源远多于 1 次")
    void withoutMutualExclusionEveryThreadLoadsItsOwn() throws Exception {
        Long id = hotIds.get(1);
        clearBothLevels(id);

        // 真 Redis、真序列化、真 L1——**只有锁是假的**，而且它"来者不拒"
        TwoLevelCache noExclusion = new TwoLevelCache(redis, json, alwaysGrantedRedisson(),
                new LocalCache(), broadcaster);
        CachedPostDetail value = sample(id);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return noExclusion.get(RedisKey.cachePostDetail(id), CachedPostDetail.class,
                        cacheProperties.postDetail().toPolicy(), () -> value);
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long loads = noExclusion.loadCount();
        System.out.printf("【反面】锁失去互斥时，%d 个并发造成的回源次数 = %d%n", THREADS, loads);

        assertThat(loads)
                .as("这一条才是上面那条的证据：只报「回源 1 次」说明不了是锁起的作用，"
                        + "把互斥拿掉之后数字必须变差")
                .isGreaterThan(10);
    }

    // ---------- 辅助 ----------

    private List<Integer> hammerHttp(Long id) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();   // 一起放出去，尽量制造"同时撞上冷缓存"的场面
                return rest.getForEntity("/api/posts/" + id, String.class)
                        .getStatusCode().value();
            }));
        }
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return statuses;
    }

    private void clearBothLevels(Long id) {
        String key = RedisKey.cachePostDetail(id);
        redis.delete(key);
        localCache.invalidate(key);
    }

    /**
     * 一把**看着像锁、实际不互斥**的假锁：{@code tryLock} 永远返回真。
     * 这与"永远抢不到锁"是两回事——后者还有轮询 L2 兜底，测不出锁的价值。
     */
    private static RedissonClient alwaysGrantedRedisson() {
        RLock lock = mock(RLock.class);
        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        return redisson;
    }

    private static CachedPostDetail sample(Long id) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 6, 12, 0);
        return new CachedPostDetail(id, "并发测试用", "正文", 1L, null,
                true, false, false, false, 0, 0, 0, List.of(), t, t);
    }
}
