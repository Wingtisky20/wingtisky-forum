package com.wingtisky.forum;

import com.wingtisky.forum.infra.cache.CacheEvictionBroadcaster;
import com.wingtisky.forum.infra.cache.LocalCache;
import com.wingtisky.forum.infra.cache.TwoLevelCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨节点失效**走真的 Redis 发布订阅**。
 *
 * <p>与 `CacheEvictionBroadcasterTest` 的分工：那边手工把消息递给另一个节点，
 * 守的是**逻辑**（该跳过的跳过、该清的清）；这边用的是**真的 Redis**，
 * 守的是**链路**——消息真的从 Redis 出来、真的被另一个订阅者收到。
 * 中间任何一环没接上（通道名写错、容器没启动、序列化不对），那边都是绿的，这边会红。
 *
 * <p><b>为什么要造一个"另一个节点"</b>：M0–M8 是单进程，真实部署下只有一个节点，
 * 广播出去只有自己收得到。所以这里**在本进程里另起一套独立的
 * L1 + 订阅容器**，把它当作另一个节点——它有自己的标识、自己的缓存、
 * 通过 Redis 而不是方法调用与本节点通信。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class CacheEvictionRedisIntegrationTest {

    private static final String KEY = "wt:cache:post:detail:it:evict";
    private static final String STALE = "另一个节点的旧值";

    @Autowired
    private TwoLevelCache cache;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    /** 另一个节点：独立的一份 L1。 */
    private LocalCache otherNodeCache;
    private CacheEvictionBroadcaster otherNode;
    private RedisMessageListenerContainer otherNodeContainer;

    @AfterEach
    void cleanUp() {
        if (otherNodeContainer != null) {
            otherNodeContainer.stop();
            otherNodeContainer = null;
        }
        redis.delete(KEY);
    }

    @Test
    @DisplayName("【跨节点·真发布订阅】本节点 evict → 广播真的送到了另一个节点，它清掉了自己那份")
    void broadcastReachesAnotherNodeOverRealRedis() throws Exception {
        startOtherNode();
        otherNodeCache.putWithTtl(KEY, STALE, Duration.ofMinutes(5));
        assertThat(otherNodeCache.get(KEY)).as("先确认另一个节点上确实有这份值").isEqualTo(STALE);

        cache.evict(KEY);

        assertThat(pollUntilCleared())
                .as("本节点 evict 之后，另一个节点的 L1 应当被广播清掉——"
                        + "这正是「只清本机」做不到的那一半")
                .isTrue();
    }

    @Test
    @DisplayName("【反面】另一个节点没订阅时，同样一次 evict 之后它那份值**还在**")
    void withoutSubscriptionTheOtherNodeKeepsTheStaleValue() throws Exception {
        // 故意不启动订阅容器
        otherNodeCache = new LocalCache();
        otherNode = new CacheEvictionBroadcaster(redis, otherNodeCache);
        otherNodeCache.putWithTtl(KEY, STALE, Duration.ofMinutes(5));

        cache.evict(KEY);
        Thread.sleep(300);      // 给广播足够的时间"到了但没人接"

        assertThat(otherNodeCache.get(KEY))
                .as("这一条才是上一条的证据：只报「被清掉了」说明不了是广播起的作用")
                .isEqualTo(STALE);
    }

    @Test
    @DisplayName("广播发不出去（没人订阅也一样）时，**本机那两份照清**——失效不依赖广播")
    void localEvictionDoesNotDependOnTheBroadcast() {
        // 本节点先把这个键缓起来
        cache.get(KEY, String.class, policy(), () -> STALE);

        cache.evict(KEY);

        // Redis 里那份应当已经删掉（广播成功与否不影响它）
        assertThat(redis.opsForValue().get(KEY)).isNull();
    }

    // ---------- 辅助 ----------

    private void startOtherNode() throws Exception {
        otherNodeCache = new LocalCache();
        otherNode = new CacheEvictionBroadcaster(redis, otherNodeCache);

        otherNodeContainer = new RedisMessageListenerContainer();
        otherNodeContainer.setConnectionFactory(connectionFactory);
        otherNodeContainer.addMessageListener(
                otherNode, new ChannelTopic(CacheEvictionBroadcaster.CHANNEL));
        otherNodeContainer.afterPropertiesSet();
        otherNodeContainer.start();

        // 订阅是异步建立的：容器"启动了"不等于"订阅已经生效"。
        // 不等的话，下面第一条广播可能落在订阅建立之前，测试会偶发地红。
        Thread.sleep(500);
    }

    /** 广播是异步的，给它最多 2 秒。 */
    private boolean pollUntilCleared() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (otherNodeCache.get(KEY) == null) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private com.wingtisky.forum.infra.cache.CachePolicy policy() {
        return new com.wingtisky.forum.infra.cache.CachePolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(100), Duration.ZERO,
                Duration.ofSeconds(10), Duration.ofMillis(200));
    }
}
