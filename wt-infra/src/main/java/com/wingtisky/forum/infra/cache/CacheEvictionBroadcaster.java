package com.wingtisky.forum.infra.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 跨节点失效：谁删了缓存，就广播一声，让**别的节点**也清掉自己进程里的 L1。
 *
 * <p><b>为什么必须有它</b>：只清本机 L1 和 Redis 的话，另一个节点里那份 L1 还是旧的，
 * 它会一直拿旧值供数，直到自己的 TTL 过期。也就是说——
 * **"写后删缓存"这条规则在多节点下靠自己是闭合不了的**，缺的就是这一步广播。
 *
 * <h3>三个刻意的设计</h3>
 *
 * <p><b>一、消息里带发送方的实例标识，收到自己发的直接跳过。</b>
 * 清两次本地缓存本身无害，带上标识的真实理由是**日志**：
 * 单进程部署（M0–M8）下所有人都只收到自己的消息，不带标识的话，
 * 日志看起来和"真的跨节点生效了"一模一样，会让人误判。
 *
 * <p><b>二、发布订阅不可靠，所以不指望它。</b> 订阅者掉线期间的消息直接丢，
 * 没有任何重发机制。兜底是 **L1 的过期时间设得比 L2 短得多**（见 {@link CachePolicy}）——
 * 消息丢了，脏数据最多活一个 L1 TTL。这里不加重试：一个"偶尔丢一条"的通知
 * 加一套重试，复杂度上去了，而它本来就是个通知，不是事实源。
 *
 * <p><b>三、这是"机制"，不是"策略"。</b>它不知道被失效的是什么业务对象——
 * 消息里只有一个键。
 */
@Component
public class CacheEvictionBroadcaster implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictionBroadcaster.class);

    /** 失效广播的通道。 */
    public static final String CHANNEL = "wt:cache:evict";

    /**
     * 实例标识与键之间的分隔符。
     *
     * <p>用 {@code |} 而不是 {@code :}：键本身就由冒号分段（{@code wt:cache:post:detail:7}），
     * 再用冒号分就把"标识"和"键"混成一段，解析时得靠位置猜。而 {@code |}` 不会出现在
     * UUID 里，也不会出现在按 {@code RedisKey} 规范生成的键里。
     */
    private static final char SEPARATOR = '|';

    private final StringRedisTemplate redis;
    private final LocalCache localCache;
    private final String instanceId;

    /**
     * 必须显式标 {@code @Autowired}：下面还有一个给测试用的构造器，
     * 一个类有**两个**构造器时 Spring 不再猜，会直接去找无参构造器然后报
     * "No default constructor found"。
     *
     * <p><b>这条已经踩过两次</b>（Task 2 的 {@code TwoLevelCache} 一次、这里一次），
     * 而两次都是**只在启动/集成测试时才炸、单测全绿**——因为单测不建 Spring 上下文。
     * 规矩定死：**类里只要多出一个构造器，主构造器就标 {@code @Autowired}。**
     */
    @Autowired
    public CacheEvictionBroadcaster(StringRedisTemplate redis, LocalCache localCache) {
        // 实例标识在构造时生成：每个进程一个。
        // 用随机值而不是主机名/IP——同一台机器上跑两个实例（M9 的实验就会这样）
        // 时，主机名分不开它们。
        this(redis, localCache, UUID.randomUUID().toString());
    }

    /** 供测试造出"两个节点"：各自一个标识，互相独立。 */
    CacheEvictionBroadcaster(StringRedisTemplate redis, LocalCache localCache, String instanceId) {
        this.redis = redis;
        this.localCache = localCache;
        this.instanceId = instanceId;
    }

    /** 广播"这个键失效了"。其它节点收到后会清掉自己那份 L1。 */
    public void broadcast(String key) {
        try {
            redis.convertAndSend(CHANNEL, instanceId + SEPARATOR + key);
        } catch (Exception e) {
            // 广播失败**不能影响写操作本身**：缓存失效晚一点（或靠 TTL 兜底）是可以接受的，
            // 而"帖子存不进去"不可接受。这是可用性优先的取舍。
            log.warn("缓存失效广播失败，其它节点将靠 L1 的 TTL 兜底：[{}]", key, e);
        }
    }

    @Override
    public void onMessage(Message message, @Nullable byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        int separator = payload.indexOf(SEPARATOR);
        if (separator <= 0) {
            // 消息格式不认识：记一条 WARN 就够，不该抛——抛出去会让订阅线程反复重试同一条坏消息
            log.warn("忽略格式无法识别的失效广播：{}", payload);
            return;
        }

        String sender = payload.substring(0, separator);
        String key = payload.substring(separator + 1);

        if (instanceId.equals(sender)) {
            // 自己发的。自己的 L1 在 evict 里已经清过了，跳过即可。
            // 这一条在单进程下**每次都会命中**，所以它也是"当前并没有真的跨节点"的证据。
            log.debug("忽略自己发出的失效广播：[{}]", key);
            return;
        }

        localCache.invalidate(key);
        log.debug("收到来自 [{}] 的失效广播，已清本地缓存：[{}]", sender, key);
    }

    /** 当前进程的实例标识（日志与测试用）。 */
    public String instanceId() {
        return instanceId;
    }
}
