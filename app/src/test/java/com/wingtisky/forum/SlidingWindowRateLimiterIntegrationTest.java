package com.wingtisky.forum;

import com.wingtisky.forum.infra.redis.SlidingWindowRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 滑动窗口限流器的边界测试。**连真实 Redis**——它跑的是 Lua 脚本，
 * 用 mock 替换掉 Redis 就等于把被测的东西换走了。
 *
 * <p><b>为什么这组测试必须存在</b>：设计 §5.1 选 ZSet 而不是计数器的理由，是
 * "固定窗口在边界能放过接近两倍流量"。**那是一个关于边界行为的论断——
 * 没有测边界的测试，它就只是一句话。**
 *
 * <p>而且这些用例**故意在窗口的中段发请求**：固定窗口的缺陷只在"窗口快要翻滚"
 * 的那一刻才显形，在窗口开头测是测不出来的。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class SlidingWindowRateLimiterIntegrationTest {

    private static final String KEY = "wt:rate:test:sliding-window";

    @Autowired
    private SlidingWindowRateLimiter limiter;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        redis.delete(KEY);
    }

    @Test
    @DisplayName("窗口内：第 limit 次放行，第 limit+1 次拒绝")
    void rejectsExactlyAfterLimit() {
        int limit = 3;
        long windowMs = 60_000;

        assertThat(allow(limit, windowMs)).isTrue();
        assertThat(allow(limit, windowMs)).isTrue();
        assertThat(allow(limit, windowMs)).isTrue();
        assertThat(allow(limit, windowMs)).isFalse();
    }

    @Test
    @DisplayName("【边界】窗口未翻滚完时，即使剩余时间不多也不放行")
    void doesNotLetThroughNearTheWindowEdge() throws InterruptedException {
        int limit = 3;
        long windowMs = 1_000;

        assertThat(allow(limit, windowMs)).isTrue();
        assertThat(allow(limit, windowMs)).isTrue();
        assertThat(allow(limit, windowMs)).isTrue();

        // 等到窗口还剩 600ms 时再打一次。
        // **固定窗口计数器在这时会怎么表现？** 若它的窗口是按"整秒"翻滚的，
        // 新的一秒开始时计数会被清零，于是这一次会被放行——连同下一批，
        // 短时间内实际通过了接近 2 倍的量。滑动窗口则照旧拒绝。
        Thread.sleep(400);
        assertThat(allow(limit, windowMs))
                .as("窗口尚未滑过，不应放行")
                .isFalse();
    }

    @Test
    @DisplayName("【边界】让 key 一直存活，才能测出'窗口在滑动'而不是'key 过期了'")
    void windowActuallySlidesWhileTheKeyStaysAlive() throws InterruptedException {
        int limit = 3;
        long windowMs = 2_000;

        // 请求**分散**在窗口内，而不是一口气打完。
        // 这一点是这条测试的关键：分散发送会让每次成功的请求都续期 PEXPIRE，
        // 于是 key 在整个过程中**始终存活**——这样就没有"key 过期导致计数归零"
        // 来帮倒忙，测出来的才是窗口本身的滑动。
        //
        // （本测试的第一版是一口气打完再睡的，结果**即使把 Lua 里的
        //   ZREMRANGEBYSCORE 整行删掉，它照样通过**——因为 key 到期了，
        //   计数自然归零。那种测试区分不了"滑动窗口"与"固定窗口"。）
        assertThat(allow(limit, windowMs)).isTrue();
        Thread.sleep(700);
        assertThat(allow(limit, windowMs)).isTrue();
        Thread.sleep(700);
        assertThat(allow(limit, windowMs)).isTrue();

        // 此刻窗口内有 3 条记录（约在 t=0 / 700 / 1400），已达上限。
        // 再等到 t≈2100：最早那条（t=0）已经滑出窗口，其余两条还在。
        Thread.sleep(700);
        assertThat(allow(limit, windowMs))
                .as("最早的一条已滑出窗口，应放行；若实现是固定窗口或不做清理，这里会是 false")
                .isTrue();
    }

    @Test
    @DisplayName("不同的 key 互不影响")
    void differentKeysAreIndependent() {
        String otherKey = KEY + ":other";
        try {
            assertThat(limiter.tryAcquire(KEY, 1, 60_000).allowed()).isTrue();
            assertThat(limiter.tryAcquire(KEY, 1, 60_000).allowed()).isFalse();
            // 换成别的 key 应能正常放行——限流是按 key 隔离的，不是全局的
            assertThat(limiter.tryAcquire(otherKey, 1, 60_000).allowed()).isTrue();
        } finally {
            redis.delete(otherKey);
        }
    }

    @Test
    @DisplayName("判定结果里带回当前计数，便于排查")
    void reportsCurrentCount() {
        SlidingWindowRateLimiter.RateLimitResult first = limiter.tryAcquire(KEY, 5, 60_000);
        SlidingWindowRateLimiter.RateLimitResult second = limiter.tryAcquire(KEY, 5, 60_000);

        assertThat(first.current()).isEqualTo(1);
        assertThat(second.current()).isEqualTo(2);
        assertThat(second.limit()).isEqualTo(5);
    }

    private boolean allow(int limit, long windowMs) {
        return limiter.tryAcquire(KEY, limit, windowMs).allowed();
    }
}
