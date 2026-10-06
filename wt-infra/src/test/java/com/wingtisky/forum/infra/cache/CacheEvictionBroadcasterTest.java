package com.wingtisky.forum.infra.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨节点失效。**这个测试的重点不是"代码写了"，而是"广播真的能清掉另一个节点"。**
 *
 * <p>M0–M8 是**单进程**，真实部署下只有一个节点——广播出去只有自己收得到，
 * "跨节点"这件事**当前无法在真实部署里验证**（spec §5.1 自己也写着
 * "多实例后跨节点失效从假变真"）。所以这里的做法是：
 * **在一个测试里造出两个互相独立的 L1 与两个标识**，模拟两个节点，
 * 再手工把消息递给另一个节点。
 *
 * <p><b>为什么这比"只写代码不验证"强</b>：不验证的话，这段代码从 M3 到 M9
 * 一直是"写了但从没生效过"，谁也不知道它是不是对的——spec §9 闸门 2 点名的
 * "验证剧场"就是这个意思。跑起来的代价很小，而它把"写了"变成"能工作"。
 */
class CacheEvictionBroadcasterTest {

    private static final String KEY = "wt:cache:post:detail:7";

    private StringRedisTemplate redis;

    /** 节点 A：发消息的那个（本地缓存随便给它一份）。 */
    private LocalCache cacheOfA;
    private CacheEvictionBroadcaster nodeA;

    /** 节点 B：**另一个进程**——独立的一份 L1、独立的标识。 */
    private LocalCache cacheOfB;
    private CacheEvictionBroadcaster nodeB;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        cacheOfA = new LocalCache();
        cacheOfB = new LocalCache();
        nodeA = new CacheEvictionBroadcaster(redis, cacheOfA, "node-A");
        nodeB = new CacheEvictionBroadcaster(redis, cacheOfB, "node-B");
    }

    @Test
    @DisplayName("广播走约定的通道，消息里带着发送方的实例标识")
    void broadcastsOnAgreedChannelWithOwnId() {
        nodeA.broadcast(KEY);

        verify(redis).convertAndSend(CacheEvictionBroadcaster.CHANNEL, "node-A|" + KEY);
    }

    @Test
    @DisplayName("【跨节点】A 广播之后，**B 自己那份** L1 被清掉了")
    void anotherNodeClearsItsOwnL1() {
        // 两个节点各自都缓存了同一个键
        cacheOfA.putWithTtl(KEY, "A 的那份", Duration.ofMinutes(1));
        cacheOfB.putWithTtl(KEY, "B 的那份", Duration.ofMinutes(1));

        nodeA.broadcast(KEY);
        deliver(nodeA, nodeB);      // 把 A 发的消息交给 B（模拟 Redis 把它投递过去）

        assertThat(cacheOfB.get(KEY))
                .as("B 必须清掉自己那份——这正是「只清本机」做不到的那一半")
                .isNull();
    }

    @Test
    @DisplayName("【自己人】A 收到自己发的消息 → 跳过，不动自己的 L1")
    void ignoresItsOwnMessage() {
        cacheOfA.putWithTtl(KEY, "A 的那份", Duration.ofMinutes(1));

        deliver(nodeA, nodeA);      // A 收到自己发的

        assertThat(cacheOfA.get(KEY))
                .as("自己发的那条不该被当成「别人说我这里失效了」——"
                        + "本地那份在 evict 里已经清过了")
                .isEqualTo("A 的那份");
    }

    @Test
    @DisplayName("【反面】消息没送到（订阅没生效）→ B 的 L1 里那份值**还在**")
    void withoutDeliveryTheOtherNodeKeepsTheStaleValue() {
        cacheOfB.putWithTtl(KEY, "B 的那份（旧的）", Duration.ofMinutes(1));

        nodeA.broadcast(KEY);
        // 故意不投递——模拟"订阅掉了"或"消息丢了"

        assertThat(cacheOfB.get(KEY))
                .as("这一条才是上一条的证据：只报「B 被清空了」说明不了是广播起的作用，"
                        + "没有投递时它必须还是旧值")
                .isEqualTo("B 的那份（旧的）");
    }

    @Test
    @DisplayName("格式不认识的消息：忽略并记 WARN，**不抛**")
    void ignoresMalformedMessage() {
        cacheOfB.putWithTtl(KEY, "B 的那份", Duration.ofMinutes(1));

        // 抛出去的话，订阅线程会反复重试同一条坏消息，把后面的正常消息也堵住
        assertThatCode(() -> nodeB.onMessage(message("这不是我们约定的格式"),
                null)).doesNotThrowAnyException();
        assertThat(cacheOfB.get(KEY)).isEqualTo("B 的那份");
    }

    @Test
    @DisplayName("广播失败（Redis 不可用）→ 只记 WARN，**不把异常抛给写操作**")
    void broadcastFailureDoesNotBreakTheCaller() {
        when(redis.convertAndSend(anyString(), any())).thenThrow(new RuntimeException("Redis 挂了"));

        // 可用性优先：缓存失效晚一点（或靠 TTL 兜底）可以接受，
        // 而"帖子存不进去"不可接受
        assertThatCode(() -> nodeA.broadcast(KEY)).doesNotThrowAnyException();
    }

    // ---------- 辅助 ----------

    /** 把 from 广播出去的那条消息，投递给 to（模拟 Redis 的发布订阅投递）。 */
    private void deliver(CacheEvictionBroadcaster from, CacheEvictionBroadcaster to) {
        to.onMessage(message(from.instanceId() + "|" + KEY), null);
    }

    private Message message(String body) {
        return new DefaultMessage(
                CacheEvictionBroadcaster.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
