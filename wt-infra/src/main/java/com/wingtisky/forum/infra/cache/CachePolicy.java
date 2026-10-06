package com.wingtisky.forum.infra.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一条缓存条目的存活策略。**这是"机制"里最接近"策略"的那一层**——
 * 它只装数字，不问这些数字该是多少（那是业务模块的事，见 architecture.md §4.3）。
 *
 * <p><b>为什么四种时间要打成一个对象、而不是四个参数</b>：它们是**一起变的**。
 * 调缓存行为时，人从来不是"只改墓碑的 TTL"——而是重新想一遍"这四档分别该多长"。
 * 分开传的话，每个调用点都是一行四个 {@link Duration}，读的人得数位置才知道哪个是哪个。
 *
 * <table>
 *   <tr><th>字段</th><th>作用</th><th>为什么单独一档</th></tr>
 *   <tr><td>{@code l1Ttl}</td><td>进程内缓存（Caffeine）的存活时间</td>
 *       <td>它比 L2 短得多，见下方"为什么 L1 要短"</td></tr>
 *   <tr><td>{@code l2Ttl}</td><td>Redis 里那份的基准存活时间</td>
 *       <td>最长的一档——它是"回源一次能顶多久"</td></tr>
 *   <tr><td>{@code l2Jitter}</td><td>写到 L2 时额外叠加的随机量</td>
 *       <td><b>防雪崩</b>：不加抖动的话，同一批写进去的 key 会同时过期，
 *           那一刻所有请求一起回源</td></tr>
 *   <tr><td>{@code nullTtl}</td><td>"这个 key 不存在"这条墓碑的存活时间</td>
 *       <td><b>必须短</b>：值不太会变，所以 L2 可以放几分钟；但"不存在"随时可能
 *           变成"存在"——墓碑留久了，新建的对象会被当成不存在</td></tr>
 * </table>
 *
 * <p><b>为什么 L1 要短</b>：L1 的失效靠 Redis 的发布订阅广播。而发布订阅
 * <b>不可靠</b>——订阅者掉线期间的消息直接丢。所以 L1 的过期时间本身就是兜底：
 * 即使那条"该失效了"的消息丢了，脏数据最多活一个 {@code l1Ttl}。
 * 把它设成和 L2 一样长，这个兜底就等于没有。
 *
 * @param l1Ttl    进程内缓存的存活时间
 * @param l2Ttl    Redis 里那份的基准存活时间
 * @param l2Jitter 写 L2 时叠加的最大随机量（防雪崩）
 * @param nullTtl  空值墓碑的存活时间
 */
public record CachePolicy(Duration l1Ttl, Duration l2Ttl, Duration l2Jitter, Duration nullTtl) {

    public CachePolicy {
        requirePositive(l1Ttl, "l1Ttl");
        requirePositive(l2Ttl, "l2Ttl");
        requirePositive(nullTtl, "nullTtl");
        if (l2Jitter == null || l2Jitter.isNegative()) {
            // 抖动可以是 0（不抖动），但不能是负数——负的会让 TTL 比基准还短，
            // 而这种错不会报错，只会让缓存"莫名其妙地不顶用"
            throw new IllegalArgumentException("l2Jitter 不能为 null 或负数：" + l2Jitter);
        }
    }

    /**
     * 这次写 L2 实际该用多长：基准值 + 一个 {@code [0, l2Jitter]} 的随机量。
     *
     * <p>每次调用都重新掷一次骰子——这正是防雪崩要的：**同一批 key 写进去，
     * 它们的到期时刻要散开**。
     */
    public Duration nextL2Ttl() {
        long jitterMs = l2Jitter.toMillis();
        if (jitterMs == 0) {
            return l2Ttl;
        }
        return l2Ttl.plusMillis(ThreadLocalRandom.current().nextLong(jitterMs + 1));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须是正数：" + value);
        }
    }
}
