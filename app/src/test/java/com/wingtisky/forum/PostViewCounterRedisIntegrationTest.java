package com.wingtisky.forum;

import com.wingtisky.forum.forum.content.cache.PostViewCounter;
import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import com.wingtisky.forum.infra.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浏览数计数器**对着真实 Redis 与真实数据库**。
 *
 * <p><b>为什么这层测试非做不可</b>：{@link PostViewCounter} 的正确性几乎全在**两段 Lua** 里
 * ——"不存在就先播种、然后自增"必须是一个原子操作。用假 Redis 能断言的只有
 * "我传了哪些 key 和参数进去"，而那证明不了 Lua 写对了。
 *
 * <p>最要紧的一条是**并发播种**：两个请求同时发现计数器不存在，
 * 各自拿库里的值去播种——没有 Lua 的话，后播种的那个会把先自增的那次**覆盖掉**，
 * 而"浏览数少了一次"是没有人会去查的那种错误。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class PostViewCounterRedisIntegrationTest {

    @Autowired
    private PostViewCounter counter;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private StringRedisTemplate redis;

    /** 本测试造出来的帖子 id，收尾时标记成已删。 */
    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            redis.delete(RedisKey.postViewCount(id));
            redis.opsForSet().remove(RedisKey.postViewDirtySet(), String.valueOf(id));
            // 逻辑删除：既让这行不再参与任何查询，也留着痕迹好排查
            postMapper.softDelete(id);
        }
        created.clear();
    }

    @Test
    @DisplayName("计数器不存在时：从库里的值播种，并返回「含本次访问」的新值")
    void seedsFromDatabaseWhenCounterIsAbsent() {
        Long id = newPost(100);

        long value = counter.increment(id, () -> postMapper.selectViewCount(id));

        assertThat(value).isEqualTo(101);
        assertThat(redis.opsForValue().get(RedisKey.postViewCount(id))).isEqualTo("101");
        assertThat(redis.opsForSet().isMember(RedisKey.postViewDirtySet(), String.valueOf(id)))
                .as("浏览数动过的帖子要记进脏集合，否则定时回写不知道要写谁")
                .isTrue();
    }

    @Test
    @DisplayName("计数器已在时：+1 且**不查库**——这是把浏览数搬去 Redis 的全部收益")
    void doesNotTouchDatabaseOnceTheCounterExists() {
        Long id = newPost(100);
        counter.increment(id, () -> postMapper.selectViewCount(id));    // 第一次：播种

        long value = counter.increment(id, () -> {
            throw new AssertionError("计数器已经在 Redis 里了，这一次不该去查库");
        });

        assertThat(value).isEqualTo(102);
    }

    @Test
    @DisplayName("【原子性】20 个线程同时第一次访问同一篇帖子：一次都不能丢")
    void seedAndIncrementIsAtomicUnderConcurrency() throws Exception {
        int threads = 20;
        Long id = newPost(0);

        // 所有线程同时撞一个**还不存在**的计数器——这是最容易丢更新的时刻：
        // 若不原子，多个线程会各自读到"键不存在"、各自用库里的 0 去播种，
        // 于是只有最后一次播种之后的那些自增被留下，前面的全被覆盖。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    counter.increment(id, () -> postMapper.selectViewCount(id));
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(failures).hasValue(0);
        assertThat(redis.opsForValue().get(RedisKey.postViewCount(id)))
                .as("20 次并发访问必须一次不少地记下来；少了就说明「播种」和「自增」不是原子的")
                .isEqualTo(String.valueOf(threads));
    }

    @Test
    @DisplayName("回写：把计数器的值写进库，并把该帖子从脏集合里移走")
    void flushesToDatabaseAndClearsTheDirtyMarker() {
        Long id = newPost(100);
        counter.increment(id, () -> postMapper.selectViewCount(id));
        counter.increment(id, () -> postMapper.selectViewCount(id));

        // 这一步平时由 @Scheduled 每 30 秒跑一次；测试里手工调，就不必等
        counter.flushToDatabase();

        assertThat(postMapper.selectViewCount(id))
                .as("库里应当是计数器的当前值（绝对值写法，回写因此是幂等的）")
                .isEqualTo(102);
        assertThat(redis.opsForSet().isMember(RedisKey.postViewDirtySet(), String.valueOf(id)))
                .as("回写过的就不该再留在脏集合里，否则每一轮都要白写一次")
                .isFalse();
    }

    @Test
    @DisplayName("回写是幂等的：连着回写两次，结果一样")
    void flushIsIdempotent() {
        Long id = newPost(100);
        counter.increment(id, () -> postMapper.selectViewCount(id));

        counter.flushToDatabase();
        int afterFirst = postMapper.selectViewCount(id);
        counter.flushToDatabase();

        assertThat(postMapper.selectViewCount(id))
                .as("写绝对值而不是 +1，所以重复回写不会把数字越加越大")
                .isEqualTo(afterFirst);
    }

    // ---------- 辅助 ----------

    /** 造一条真实的帖子行，返回它的 id。 */
    private Long newPost(int viewCount) {
        Post post = new Post();
        post.setAuthorId(1L);
        post.setTitle("浏览数测试");
        post.setContent("正文");
        post.setSummary("摘要");
        post.setStatus(Post.STATUS_PUBLISHED);
        post.setDeleted(0);
        post.setTopFlag(0);
        post.setFeaturedFlag(0);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCollectCount(0);
        post.setCreateTime(LocalDateTime.of(2026, 10, 6, 10, 0));
        post.setUpdateTime(LocalDateTime.of(2026, 10, 6, 10, 0));
        postMapper.insert(post);

        // ⚠️ `insert` 只写 (author_id, title, content, summary) 四列，
        // view_count 走数据库默认值 0——**setViewCount 是设不进去的**
        // （第一版测试就是栽在这里：期望 101 拿到 1）。
        // 所以要一个指定的基准值，得用 updateViewCount 单独设一次。
        postMapper.updateViewCount(post.getId(), viewCount);

        created.add(post.getId());
        return post.getId();
    }
}
