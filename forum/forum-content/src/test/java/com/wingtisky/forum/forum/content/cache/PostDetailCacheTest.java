package com.wingtisky.forum.forum.content.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.forum.content.dto.TagView;
import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.infra.cache.CacheEvictionBroadcaster;
import com.wingtisky.forum.infra.cache.CachePolicy;
import com.wingtisky.forum.infra.cache.LocalCache;
import com.wingtisky.forum.infra.cache.TwoLevelCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PostDetailCache} 与 {@link CachedPostDetail} 的测试。
 *
 * <p>这里守的不是"缓存能不能用"（那是 {@code TwoLevelCacheTest} 的活），
 * 而是**这一层业务决定**：缓存对象的**字段清单**、**不可变**、
 * 以及键的生成符合规范。
 */
class PostDetailCacheTest {

    private static final Long POST_ID = 7L;

    /** 与生产配置里的初始值一致，见 `application.yml` 的 `wt.cache` 段。 */
    private static final CachePolicy DEFAULT_POLICY = new CachePolicy(
            Duration.ofSeconds(45), Duration.ofMinutes(5), Duration.ofSeconds(60),
            Duration.ofSeconds(60), Duration.ofMillis(500));

    private StringRedisTemplate redis;
    private Map<String, String> l2;

    /** 哪些 key 被以多长的 TTL 写进了 L2——用来证明**配置真的生效了**。 */
    private Map<String, Duration> writtenTtl;

    private TwoLevelCache twoLevelCache;
    private PostDetailCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        // InterruptedException：RLock.tryLock(long, TimeUnit) 是会抛的
        // （它继承 java.util.concurrent.locks.Lock），打桩时得让它过去
        l2 = new HashMap<>();
        writtenTtl = new HashMap<>();

        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(anyString())).thenAnswer(inv -> l2.get(inv.getArgument(0)));
        doAnswer(inv -> {
            l2.put(inv.getArgument(0), inv.getArgument(1));
            writtenTtl.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        doAnswer(inv -> {
            l2.remove(inv.getArgument(0));
            writtenTtl.remove(inv.getArgument(0));
            return Boolean.TRUE;
        }).when(redis).delete(anyString());

        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);

        twoLevelCache = new TwoLevelCache(redis, objectMapper(), redisson, new LocalCache(),
                mock(CacheEvictionBroadcaster.class));
        cache = new PostDetailCache(twoLevelCache, props(true));
    }

    /** 造一份配置。默认开关打开、数值与生产初始值一致。 */
    private static CacheProperties props(boolean enabled) {
        return props(enabled, DEFAULT_POLICY);
    }

    private static CacheProperties props(boolean enabled, CachePolicy policy) {
        return new CacheProperties(
                new CacheProperties.PostDetail(enabled,
                        policy.l1Ttl(), policy.l2Ttl(), policy.l2Jitter(),
                        policy.nullTtl(), policy.lockWait()),
                new CacheProperties.ViewCount(Duration.ofSeconds(30)));
    }

    // ---------- 结构性守卫：缓存对象的字段清单 ----------

    @Test
    @DisplayName("【守门】缓存对象里**没有** viewCount / liked / collected")
    void cacheObjectExcludesViewerSpecificAndEverChangingFields() {
        Set<String> components = Arrays.stream(CachedPostDetail.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(components)
                .as("视图数每次读都在变——它进缓存的话，缓存从存在那一刻起就是脏的。"
                        + "它走 Redis 计数器")
                .doesNotContain("viewCount");
        assertThat(components)
                .as("liked / collected 是**用户私有状态**。塞进公共缓存是「命中率」和"
                        + "「越权风险」两样一起赔——缓存串号 = 别人看到你点过赞")
                .doesNotContain("liked", "collected");
    }

    @Test
    @DisplayName("【守门】可见性判定所需的 visible 必须在，否则命中缓存时判不了鉴权")
    void cacheObjectCarriesVisibilityFlag() {
        Set<String> components = Arrays.stream(CachedPostDetail.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(components)
                .as("缓存的是**数据**，不是**权限判定的结果**——命中缓存也必须重新判可见性")
                .contains("visible");
    }

    // ---------- 对象本身 ----------

    @Test
    @DisplayName("缓存对象是**拷贝**：改传入的 tags 列表，缓存里那份不受影响")
    void copiesTheTagList() {
        List<TagView> mutable = new ArrayList<>(List.of(new TagView(1L, "Redis", 3)));

        CachedPostDetail cached = CachedPostDetail.from(publishedPost(), author(), mutable);

        mutable.clear();

        // 不拷的话，调用方后续对这个列表的任何改动都会**静默改掉缓存**
        // ——而且没有任何代码删过这个 key，查不出来（设计稿 §7）
        assertThat(cached.tags()).hasSize(1);
        assertThat(cached.tags()).isUnmodifiable();
    }

    @Test
    @DisplayName("从原料组装：计数与治理标记都带上了，viewCount 不带")
    void assemblesFromRawMaterial() {
        CachedPostDetail cached = CachedPostDetail.from(publishedPost(), author(), List.of());

        assertThat(cached.id()).isEqualTo(POST_ID);
        assertThat(cached.title()).isEqualTo("标题");
        assertThat(cached.visible()).isTrue();
        assertThat(cached.top()).isFalse();
        assertThat(cached.likeCount()).isEqualTo(3);
        assertThat(cached.commentCount()).isEqualTo(5);
        assertThat(cached.collectCount()).isEqualTo(2);
    }

    // ---------- 取 / 删 ----------

    @Test
    @DisplayName("键的生成符合 RedisKey 的规范，且取与删用的是同一个键")
    void usesTheAgreedKey() {
        cache.get(POST_ID, this::published);

        assertThat(l2).containsKey("wt:cache:post:detail:" + POST_ID);

        cache.evict(POST_ID);

        assertThat(l2).doesNotContainKey("wt:cache:post:detail:" + POST_ID);
    }

    @Test
    @DisplayName("【防穿透】帖子不存在 → 写墓碑 → 再取同一个 id 不会再回源")
    void cachesMissingPostAsTombstone() {
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get(POST_ID, () -> {
            loads.incrementAndGet();
            return null;
        })).isNull();

        assertThat(l2.get("wt:cache:post:detail:" + POST_ID)).isEqualTo("__NULL__");

        assertThat(cache.get(POST_ID, () -> {
            loads.incrementAndGet();
            return null;
        })).isNull();
        assertThat(loads)
                .as("没有墓碑的话，反复查一个不存在的 id 就能绕开缓存直接压库")
                .hasValue(1);
    }

    @Test
    @DisplayName("evict 之后会重新回源")
    void reloadsAfterEviction() {
        cache.get(POST_ID, this::published);
        cache.evict(POST_ID);

        CachedPostDetail afterEvict = cache.get(POST_ID, () -> {
            Post post = publishedPost();
            post.setTitle("改过的标题");
            return CachedPostDetail.from(post, author(), List.of());
        });

        assertThat(afterEvict.title()).isEqualTo("改过的标题");
    }

    // ---------- 配置段与总开关（Task 9） ----------

    @Test
    @DisplayName("★ 总开关关掉：**根本不碰两级缓存**，直接回源，Redis 里一个新 key 都不出现")
    void disabledSwitchBypassesCacheEntirely() {
        PostDetailCache off = new PostDetailCache(twoLevelCache, props(false));
        AtomicInteger loads = new AtomicInteger();

        CachedPostDetail value = off.get(POST_ID, () -> {
            loads.incrementAndGet();
            return published();
        });

        assertThat(value.title()).isEqualTo("标题");
        assertThat(loads).hasValue(1);
        assertThat(l2)
                .as("这一条不成立，Task 10 的「纯 MySQL」对照组测的就是同一套代码——"
                        + "而那个实验看起来会很像真的")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 总开关关掉后，写路径的 evict 也是空操作——缓存这一层等于不存在")
    void disabledSwitchMakesEvictANoOp() {
        PostDetailCache off = new PostDetailCache(twoLevelCache, props(false));

        off.evictAfterCommit(POST_ID);
        off.evict(POST_ID);

        // 没有断言"缓存里还有东西"——装东西进去要先经过一个"开着"的实例，
        // 而这里要证明的只是：关掉之后**它不去动 Redis**
        assertThat(l2).isEmpty();
    }

    @Test
    @DisplayName("★ 存活时长来自配置：改配置，写进 L2 的 TTL 跟着变")
    void policyComesFromConfiguration() {
        Duration l2Ttl = Duration.ofSeconds(90);
        Duration jitter = Duration.ofSeconds(10);
        PostDetailCache configured = new PostDetailCache(twoLevelCache,
                props(true, new CachePolicy(Duration.ofSeconds(7), l2Ttl, jitter,
                        Duration.ofSeconds(3), Duration.ofMillis(123))));

        configured.get(POST_ID, this::published);

        // 不直接断言"传进去的 policy 是哪个"（那只是证明参数传对了），
        // 而是看**真正写进 L2 的那个 TTL**：它必然落在 [l2Ttl, l2Ttl + jitter] 里。
        // 用生产初始值（5 分钟 + 60 秒）是落不到这个区间的。
        assertThat(writtenTtl.get("wt:cache:post:detail:" + POST_ID))
                .isNotNull()
                .isBetween(l2Ttl, l2Ttl.plus(jitter));
    }

    // ---------- 辅助 ----------

    private Post publishedPost() {
        Post post = new Post();
        post.setId(POST_ID);
        post.setTitle("标题");
        post.setContent("正文");
        post.setAuthorId(9L);
        post.setStatus(Post.STATUS_PUBLISHED);
        post.setDeleted(0);
        post.setTopFlag(0);
        post.setFeaturedFlag(0);
        post.setViewCount(100);
        post.setLikeCount(3);
        post.setCommentCount(5);
        post.setCollectCount(2);
        post.setCreateTime(LocalDateTime.of(2026, 10, 6, 10, 0));
        post.setUpdateTime(LocalDateTime.of(2026, 10, 6, 11, 0));
        return post;
    }

    private CachedPostDetail published() {
        return CachedPostDetail.from(publishedPost(), author(), List.of());
    }

    private UserBrief author() {
        return new UserBrief(9L, "作者", null);
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
