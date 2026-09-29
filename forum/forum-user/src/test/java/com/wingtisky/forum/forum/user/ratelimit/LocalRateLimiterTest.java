package com.wingtisky.forum.forum.user.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存兜底限流器的边界测试。
 *
 * <p>它是"Redis 挂了"时的降级路径——**平时不跑，出事时才跑**。
 * 这种代码最容易被忽略：正常流量永远走不到它，所以它的 bug 也不会在
 * 正常流量里暴露。只有在 Redis 真的挂掉那一次，你才会知道它能不能用。
 */
class LocalRateLimiterTest {

    private final LocalRateLimiter limiter = new LocalRateLimiter();

    @Test
    @DisplayName("窗口内：第 limit 次放行，第 limit+1 次拒绝")
    void rejectsExactlyAfterLimit() {
        String key = "k1";
        int limit = 3;

        assertThat(limiter.tryAcquire(key, limit, 60_000)).isTrue();
        assertThat(limiter.tryAcquire(key, limit, 60_000)).isTrue();
        assertThat(limiter.tryAcquire(key, limit, 60_000)).isTrue();
        // 第 4 次
        assertThat(limiter.tryAcquire(key, limit, 60_000)).isFalse();
    }

    @Test
    @DisplayName("窗口过去之后恢复放行")
    void recoversAfterWindowElapses() throws InterruptedException {
        String key = "k2";
        long windowMs = 200;

        assertThat(limiter.tryAcquire(key, 1, windowMs)).isTrue();
        assertThat(limiter.tryAcquire(key, 1, windowMs)).isFalse();

        Thread.sleep(windowMs + 80);

        assertThat(limiter.tryAcquire(key, 1, windowMs)).isTrue();
    }

    @Test
    @DisplayName("不同的 key 互不影响——一个 IP 被限不该连累另一个")
    void differentKeysAreIndependent() {
        assertThat(limiter.tryAcquire("ip-a", 1, 60_000)).isTrue();
        assertThat(limiter.tryAcquire("ip-a", 1, 60_000)).isFalse();
        // ip-b 不受 ip-a 的影响
        assertThat(limiter.tryAcquire("ip-b", 1, 60_000)).isTrue();
    }

    @Test
    @DisplayName("跟踪的 key 数量可观测（用于确认兜底真的在被使用）")
    void tracksKeys() {
        limiter.tryAcquire("x", 1, 60_000);
        limiter.tryAcquire("y", 1, 60_000);

        assertThat(limiter.trackedKeys()).isEqualTo(2);
    }
}
