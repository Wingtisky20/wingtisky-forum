package com.wingtisky.forum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redisson 接入的第一关。**它问的不是业务问题，是两个环境问题。**
 *
 * <p><b>一、Redisson 3.52.0 能不能跟本机的 Redis 5.0.14.1 说上话。</b>
 * 这是 STATUS 待核实表里挂了很久的一条：以前只做过编译期验证，
 * 而本机 Redis 停在 5.0.14.1 是 Windows 原生构建的天花板（没有更高版本，见 ADR-0004），
 * Redisson 却可能用到 Redis 6+ 才有的命令。**编译通过证明不了运行时能用**，
 * 只有真的加一次锁才算数。若报 {@code ERR unknown command}，降版本并修订 ADR-0001。
 *
 * <p><b>二、它有没有把 M1/M2 已经在用的那条 Redis 路弄坏。</b>
 * {@code redisson-spring-boot-starter} 会装配自己的连接工厂，
 * **有可能顶掉原来那个 Lettuce 的**。真被顶掉时不会启动报错，
 * 而是令牌刷新、限流这些**已经跑通的功能**在某次请求上出错——那是最难查的一类问题。
 * 所以这一关的验证顺序是：**先证明旧路还在，再谈新功能**。
 *
 * <p>连真实 Redis（本机 6379）。这也正是 {@code @Tag("integration")} 的意义：
 * 这些事在 CI 上证明不了，只能在有中间件的机器上跑（ADR-0007）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class RedissonSmokeIntegrationTest {

    private static final String LOCK_KEY = "wt:lock:test:redisson-smoke";
    private static final String SHARED_KEY = "wt:test:redisson-smoke:shared";

    /**
     * {@code required = false}：若 starter 没装配出这个 Bean，
     * 这里会是 null，而下面那条断言会给出**明确的失败信息**——
     * 比"Spring 启动时找不到 Bean"绕一大圈才报出来要好定位。
     */
    @Autowired(required = false)
    private RedissonClient redisson;

    /** M1 起就在用的那个客户端（底层 Lettuce）。它必须还能干活。 */
    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("starter 自动装配出了 RedissonClient")
    void clientIsAutoConfigured() {
        assertThat(redisson)
                .as("redisson-spring-boot-starter 应当自动装配出 RedissonClient")
                .isNotNull();
    }

    @Test
    @DisplayName("能对 Redis 5.0.14.1 加锁并释放——不报 ERR unknown command")
    void canLockAndUnlock() throws InterruptedException {
        RLock lock = redisson.getLock(LOCK_KEY);
        try {
            // 最多等 1 秒拿锁，锁最多持有 5 秒（超过 5 秒由看门狗续期）
            assertThat(lock.tryLock(1, 5, TimeUnit.SECONDS))
                    .as("Redisson 应当能在 Redis 5.0.14.1 上加锁")
                    .isTrue();
            assertThat(lock.isHeldByCurrentThread()).isTrue();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    @DisplayName("【关键】Lettuce 那条路仍然能读写——两个客户端没有互相顶掉")
    void lettucePathStillWorks() {
        String key = "wt:test:redisson-smoke:lettuce";
        try {
            redis.opsForValue().set(key, "written-by-lettuce", Duration.ofSeconds(10));
            assertThat(redis.opsForValue().get(key)).isEqualTo("written-by-lettuce");
        } finally {
            redis.delete(key);
        }
    }

    @Test
    @DisplayName("两个客户端连的是同一个 Redis 实例（Lettuce 写的，Redisson 读得到）")
    void bothClientsSeeTheSameServer() {
        try {
            redis.opsForValue().set(SHARED_KEY, "written-by-lettuce", Duration.ofSeconds(10));

            // ⚠️ 必须显式给 StringCodec，不能用默认的 getBucket(key)。
            //
            // 这条是实测踩出来的：Redisson 的**默认编解码器是二进制的**（Kryo），
            // 拿它去读 StringRedisTemplate 写的纯字符串，会直接抛
            // `KryoException: Encountered unregistered class ID: 117` ——
            // 因为它在拿 Kryo 的格式去解一段明文。
            //
            // 本设计里 Redisson **只用于锁**，不与其他客户端交换数据，所以不受影响；
            // 但将来若有人用 Redisson 的 RBucket / RMap 存业务数据，
            // **必须显式指定 StringCodec 或 JsonCodec**，否则写进去的是二进制，
            // 而 RedisInsight 里看是一串乱码、StringRedisTemplate 也读不出来。
            RBucket<String> bucket = redisson.getBucket(SHARED_KEY, StringCodec.INSTANCE);
            assertThat(bucket.get())
                    .as("Redisson 应当读到 Lettuce 刚写的值——证明两者连的是同一个实例，"
                            + "而不是各自连了一个（后者会让缓存与锁作用于不同的数据）")
                    .isEqualTo("written-by-lettuce");
        } finally {
            redis.delete(SHARED_KEY);
        }
    }
}
