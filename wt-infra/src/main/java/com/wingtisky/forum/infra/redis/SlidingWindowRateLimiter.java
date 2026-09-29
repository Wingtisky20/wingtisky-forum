package com.wingtisky.forum.infra.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis ZSet 的滑动窗口限流器（**机制**，不含"限多少"这类策略）。
 *
 * <p><b>为什么不给这个类传阈值和窗口</b>：那是业务策略，属于调用方。
 * 这里只回答"给定 key、窗口、上限，这次请求过不过"。这条线就是
 * architecture.md §4.3 画的「机制 vs 策略」。
 *
 * <h2>为什么用 ZSet 而不是计数器</h2>
 * 固定窗口计数器（{@code INCR} + 过期）有一个经典缺陷：**窗口边界能放过接近两倍的流量**。
 * 假设限制"每分钟 100 次"，用户在 00:59 打满 100 次，01:00 又打满 100 次——
 * 跨边界的那两秒里实际过了 200 次，而计数器认为一切正常。
 * ZSet 把每次请求的时间戳存成 score，每次只统计"最近 N 秒内"的成员，没有边界问题。
 *
 * <h2>为什么必须用 Lua</h2>
 * 判定要四步：清窗口外的 → 数窗口内的 → 判断 → 记录本次。
 * **分开执行的话，两次请求之间会插进来别的请求**：A 数完发现没超限，
 * 还没来得及记录，B 也数完发现没超限——两个都放行，限流漏掉。
 * 这与秒杀扣库存是同一类问题，解法也一样：把判定与写入放进一次原子操作。
 */
@Component
public class SlidingWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    /**
     * KEYS[1] = 限流的 key
     * ARGV[1] = 当前时间戳（毫秒）
     * ARGV[2] = 窗口长度（毫秒）
     * ARGV[3] = 窗口内允许的最大请求数
     * ARGV[4] = 本次请求的唯一标识（ZSet 的 member）
     *
     * 返回 {是否放行(1/0), 窗口内当前计数}
     *
     * <p>member 必须**唯一**，由调用方传入而不是在脚本里生成：如果拿时间戳当 member，
     * 同一毫秒内的两个请求会互相覆盖，ZSet 里的记录数少于实际请求数——
     * 限流会**静默地放过多余的请求**。
     *
     * <p>脚本里不用 {@code math.random}：Redis 要求脚本可重放（否则主从复制会不一致），
     * 随机数会让副本算出不同结果。
     */
    private static final String SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            -- 1. 清掉窗口外的记录（score 是时间戳）
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

            -- 2. 数窗口内还剩多少
            local count = redis.call('ZCARD', key)

            -- 3. 已达上限则拒绝，且**不记录本次**
            if count >= limit then
                return {0, count}
            end

            -- 4. 未超限：记录本次并续期
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window)
            return {1, count + 1}
            """;

    /** 脚本在服务端缓存，不需要每次传输脚本体。 */
    private final DefaultRedisScript<List> script;

    private final StringRedisTemplate redis;

    public SlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptText(SCRIPT);
        s.setResultType(List.class);
        this.script = s;
    }

    /**
     * 判定一次请求。
     *
     * @param key       限流 key（用 {@link RedisKey#rateLimit} 构造）
     * @param limit     窗口内允许的最大请求数
     * @param windowMs  窗口长度（毫秒）
     * @return 判定结果；**Redis 不可用时抛异常**，由调用方决定如何降级——
     *         这个类不知道该放行还是该拒绝，那是策略
     */
    public RateLimitResult tryAcquire(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        // member 唯一即可；nanoTime 在同一 JVM 内单调，配合纳秒后缀足够避免碰撞
        String member = now + "-" + System.nanoTime();

        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) redis.execute(
                script, List.of(key), String.valueOf(now), String.valueOf(windowMs),
                String.valueOf(limit), member);

        if (result == null || result.size() < 2) {
            // 脚本返回值异常（理论上不会发生）。当成"放行"会让限流静默失效，
            // 当成"拒绝"会让全站不可用——两者都不该由这里决定。
            throw new IllegalStateException("限流脚本返回了非预期的结果: " + result);
        }
        return new RateLimitResult(result.get(0) == 1L, result.get(1).intValue(), limit);
    }

    /**
     * 判定结果。
     *
     * @param allowed   是否放行
     * @param current   窗口内当前计数（用于日志与排查，回给前端则可能变成信息泄露）
     * @param limit     上限
     */
    public record RateLimitResult(boolean allowed, int current, int limit) {
    }
}
