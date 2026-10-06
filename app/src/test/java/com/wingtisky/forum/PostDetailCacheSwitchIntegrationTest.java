package com.wingtisky.forum;

import com.wingtisky.forum.forum.content.cache.CacheProperties;
import com.wingtisky.forum.forum.content.cache.CachedPostDetail;
import com.wingtisky.forum.forum.content.cache.PostDetailCache;
import com.wingtisky.forum.infra.cache.CachePolicy;
import com.wingtisky.forum.infra.cache.TwoLevelCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 总开关**真的能关掉缓存**——对着真实 Redis 验。
 *
 * <p><b>为什么这条非做不可</b>：Task 10 的闸门实验要拿「纯 MySQL」和「三级缓存」
 * 两组数据比命中率与延迟，而两组必须是**同一份代码、只翻这个开关**。
 * 开关要是不生效，那个实验的"对照组"测的其实是同一套代码——
 * **结论就是假的，而它看起来会很像真的**。
 *
 * <p><b>为什么两个方向都要测</b>：只测"关掉之后 Redis 里没东西"是不够的——
 * 一个把缓存整个写坏了的改动也能让这条通过。所以必须配上"打开之后
 * Redis 里**确实有**东西"这条反面对照（spec §9 闸门 2：单边数据不算证据）。
 *
 * <p>用两个不同的帖子 id 把两次测试隔开：同一个 id 的话，前一次留在 L1
 * （进程内那份）里的值会让后一次不写 Redis，于是"打开"那条会假红。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class PostDetailCacheSwitchIntegrationTest {

    private static final Long ID_WHEN_DISABLED = 9001L;
    private static final Long ID_WHEN_ENABLED = 9002L;

    @Autowired
    private TwoLevelCache twoLevelCache;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        redis.delete(key(ID_WHEN_DISABLED));
        redis.delete(key(ID_WHEN_ENABLED));
    }

    @Test
    @DisplayName("★ 开关关掉：直接回源，Redis 里**一个新 key 都不出现**")
    void disabledSwitchLeavesNothingInRedis() {
        PostDetailCache off = new PostDetailCache(twoLevelCache, props(false));
        AtomicInteger loads = new AtomicInteger();

        CachedPostDetail value = off.get(ID_WHEN_DISABLED, () -> {
            loads.incrementAndGet();
            return sample(ID_WHEN_DISABLED);
        });

        assertThat(value.title()).as("关掉缓存不等于关掉功能，值照样要拿得到").isEqualTo("标题");
        assertThat(loads).as("每次都回源——这正是「纯 MySQL」那一组的行为").hasValue(1);
        assertThat(redis.hasKey(key(ID_WHEN_DISABLED)))
                .as("开关不生效的话，Task 10 的对照组测的就是同一套代码")
                .isFalse();
    }

    @Test
    @DisplayName("【反面对照】开关打开：同一个 key **真的写进了 Redis**，而且带着过期时间")
    void enabledSwitchWritesToRedis() {
        PostDetailCache on = new PostDetailCache(twoLevelCache, props(true));

        on.get(ID_WHEN_ENABLED, () -> sample(ID_WHEN_ENABLED));

        assertThat(redis.hasKey(key(ID_WHEN_ENABLED)))
                .as("没有这条，上面那条可能只是「缓存整个坏了」，而不是「开关生效了」")
                .isTrue();
        assertThat(redis.getExpire(key(ID_WHEN_ENABLED)))
                .as("写进去还得带着过期时间，否则防雪崩那套（抖动）根本没机会生效")
                .isGreaterThan(0L);
    }

    private static String key(Long postId) {
        return "wt:cache:post:detail:" + postId;
    }

    private static CachedPostDetail sample(Long postId) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 6, 12, 0);
        return new CachedPostDetail(postId, "标题", "正文", 9L, null,
                true, false, false, false, 0, 0, 0, List.of(), t, t);
    }

    private static CacheProperties props(boolean enabled) {
        return new CacheProperties(
                new CacheProperties.PostDetail(enabled,
                        Duration.ofSeconds(45), Duration.ofMinutes(5), Duration.ofSeconds(60),
                        Duration.ofSeconds(60), Duration.ofMillis(500)),
                new CacheProperties.ViewCount(Duration.ofSeconds(30)));
    }
}
