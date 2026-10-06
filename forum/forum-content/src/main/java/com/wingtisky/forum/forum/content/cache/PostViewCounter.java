package com.wingtisky.forum.forum.content.cache;

import com.wingtisky.forum.forum.content.mapper.PostMapper;
import com.wingtisky.forum.infra.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 浏览数：**Redis 里数，定时回写库**。
 *
 * <p><b>它解决什么</b>：M2 的详情接口是"读接口里带写"——每看一次就
 * {@code UPDATE t_post SET view_count = view_count + 1}。这是**每一次读都写一次库**，
 * 流量上来只会越来越慢，而浏览数恰恰是全站最频繁的写。
 *
 * <p><b>换成的做法</b>：Redis 里放一个**绝对值**计数器，读的时候自增并返回；
 * 每 30 秒把脏的那些回写进库。
 *
 * <p><b>为什么存绝对值而不是增量</b>：回写就变成幂等的（重复写同一个值，结果一样）。
 * 增量方案下，"写进去了但没来得及标记完成"会导致重试时又加一遍——
 * 而浏览数是只增不减的，加错了没人会发现。
 *
 * <p><b>一致性：弱，而且是有意接受的。</b> architecture.md §2.1 明写
 * {@code forum-service} 的一致性要求是"最终一致可接受（**浏览数**、热度）"。
 * Redis 重启会丢最近一段的增量——这条代价换来的，是读接口不再写库。
 * **这一条要写进交付说明，不藏着。**
 *
 * <p><b>为什么用 Lua</b>："不存在就先播种、然后自增"这两步必须原子。
 * 分开两次调用的话，两个并发请求会各自播种一次（都以为键不存在），
 * 结果是其中一次的播种把另一次的增量覆盖掉——而"浏览数少了几次"没人查得出来。
 */
@Component
public class PostViewCounter {

    private static final Logger log = LoggerFactory.getLogger(PostViewCounter.class);

    /**
     * 计数器已存在 → 自增并返回；不存在 → 返回 -1 让调用方去拿基准值。
     *
     * <p>KEYS[1] 计数器，KEYS[2] 脏集合；ARGV[1] 帖子 id。
     */
    private static final RedisScript<Long> INCR_IF_PRESENT = script("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return -1
            end
            redis.call('SADD', KEYS[2], ARGV[1])
            return redis.call('INCR', KEYS[1])
            """);

    /**
     * 不存在就先播种再自增，**一步完成**（见类注释里"为什么用 Lua"）。
     *
     * <p>KEYS[1] 计数器，KEYS[2] 脏集合；ARGV[1] 帖子 id，ARGV[2] 基准值（库里的当前值）。
     */
    private static final RedisScript<Long> SEED_AND_INCR = script("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('SET', KEYS[1], ARGV[2])
            end
            redis.call('SADD', KEYS[2], ARGV[1])
            return redis.call('INCR', KEYS[1])
            """);

    private final StringRedisTemplate redis;
    private final PostMapper postMapper;

    public PostViewCounter(StringRedisTemplate redis, PostMapper postMapper) {
        this.redis = redis;
        this.postMapper = postMapper;
    }

    /**
     * 让某条帖子的浏览数 +1，并返回新值。
     *
     * @param baselineSupplier **只在计数器不存在时才会被调用**——它要去查一次库拿基准值。
     *                         用 {@code Supplier} 而不是直接传值，是为了让正常路径
     *                         （计数器在）**一次库都不查**。传值的话，
     *                         每次读详情都会白查一次，"搬去 Redis"就白搬了
     * @return 新的浏览数；帖子已不存在时返回 0
     */
    public long increment(Long postId, Supplier<Integer> baselineSupplier) {
        List<String> keys = viewKeys(postId);
        String postIdArg = String.valueOf(postId);

        Long current = redis.execute(INCR_IF_PRESENT, keys, postIdArg);
        if (current != null && current >= 0) {
            return current;
        }

        Integer baseline = baselineSupplier.get();
        if (baseline == null) {
            return 0L;
        }
        Long seeded = redis.execute(SEED_AND_INCR, keys, postIdArg, String.valueOf(baseline));
        return seeded == null ? baseline : seeded;
    }

    /**
     * 把脏帖子（浏览数动过的）回写进库。
     *
     * <p>定时执行。**失败不抛**——下一次还会再试，而抛出去只会让定时任务线程打日志。
     *
     * <p>顺序是"先取值、再写库、最后从脏集合里移除"：若中途失败，
     * 下一次它还在脏集合里，会被重试。若先移除再写库，失败一次这条帖子的浏览数
     * 就永远停在旧值上了（除非它再被看一次）。
     */
    @Scheduled(fixedDelayString = "${wt.cache.view-count.flush-interval:30000}")
    public void flushToDatabase() {
        Set<String> dirty;
        try {
            dirty = redis.opsForSet().members(RedisKey.postViewDirtySet());
        } catch (DataAccessException e) {
            log.warn("读取浏览数脏集合失败，本次回写跳过", e);
            return;
        }
        if (dirty == null || dirty.isEmpty()) {
            return;
        }

        int flushed = 0;
        for (String rawId : dirty) {
            Long postId;
            try {
                postId = Long.valueOf(rawId);
            } catch (NumberFormatException e) {
                // 集合里混进了不是 id 的东西：移掉它，否则每次回写都要卡在这一条上
                redis.opsForSet().remove(RedisKey.postViewDirtySet(), rawId);
                continue;
            }
            try {
                String value = redis.opsForValue().get(RedisKey.postViewCount(postId));
                if (value == null) {
                    // 计数器已经没了（过期或被清）：没有可写的东西，把它从脏集合里抹掉
                    redis.opsForSet().remove(RedisKey.postViewDirtySet(), rawId);
                    continue;
                }
                postMapper.updateViewCount(postId, Integer.parseInt(value));
                redis.opsForSet().remove(RedisKey.postViewDirtySet(), rawId);
                flushed++;
            } catch (RuntimeException e) {
                // 单条失败不影响其余：留着它下次再试
                log.warn("回写浏览数失败，下次重试：[postId={}]", postId, e);
            }
        }
        if (flushed > 0) {
            log.debug("浏览数回写完成：{} 条", flushed);
        }
    }

    private List<String> viewKeys(Long postId) {
        return List.of(RedisKey.postViewCount(postId), RedisKey.postViewDirtySet());
    }

    private static RedisScript<Long> script(String lua) {
        return new DefaultRedisScript<>(lua, Long.class);
    }
}
