package com.wingtisky.forum.forum.user.ratelimit;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 不可用时的单机内存限流兜底。
 *
 * <p><b>它比 fail-open 强在哪</b>：登录接口的限流**唯一目的就是防撞库**。
 * Redis 一挂就全放行，等于在最需要保护的时候把门打开。退到内存计数虽然不精确，
 * 但攻击者仍然会受到限制。
 *
 * <p><b>它比 Redis 版差在哪，必须说清楚</b>：
 * <ul>
 *   <li>**是固定窗口，不是滑动窗口**——存在设计 §5.1 说的边界问题
 *       （窗口交界处可能放过接近两倍的量）。作为临时兜底可以接受，
 *       但不要把它当成 Redis 版的等价替代</li>
 *   <li>**M9 拆多实例后每个实例各限一份**：N 个实例实际放行 N 倍。
 *       那时要么给 Redis 上哨兵、要么把登录接口改成 fail-closed。
 *       现在不做（YAGNI），但记在这里</li>
 * </ul>
 */
@Component
public class LocalRateLimiter {

    /**
     * 条数上限。兜底只在 Redis 故障期间生效，正常情况下这个 Map 是空的；
     * 但万一 Redis 长时间不可用，不设上限的话它会被无限的 IP 撑爆内存——
     * **兜底机制自己把服务搞崩**是最糟的失败方式。
     */
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @return 是否放行
     */
    public boolean tryAcquire(String key, int limit, long windowMs) {
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            // 满了就拒绝新 key 而不是无脑放行：宁可多拦一些，
            // 也不能让内存无限增长。已有的 key 继续正常计数。
            return false;
        }

        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.startMillis >= windowMs) {
                return new Window(now);
            }
            return existing;
        });
        return window.count.incrementAndGet() <= limit;
    }

    /** 供测试与排查用：当前跟踪的 key 数量。 */
    public int trackedKeys() {
        return windows.size();
    }

    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
