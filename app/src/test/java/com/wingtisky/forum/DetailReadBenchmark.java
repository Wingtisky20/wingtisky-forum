package com.wingtisky.forum;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.wingtisky.forum.infra.cache.LocalCache;
import com.wingtisky.forum.infra.cache.TwoLevelCache;
import com.wingtisky.forum.infra.redis.RedisKey;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

/**
 * 「详情读」的测量代码——闸门实验的两组**共用同一段测量逻辑**，只有开关不同。
 *
 * <p><b>为什么它是一份共用的静态工具、而不是各写一遍</b>：两组的可比性靠"只差一个变量"。
 * 测量代码要是各写一份，两边的循环、计时点、计数方式稍有出入，差值里就混进了
 * 别的东西——**而这正是本项目在闸门设计里反复说明要避免的那件事**。
 *
 * <h3>三处刻意的设计</h3>
 *
 * <p><b>一、数据库被读了多少次，是去 MySQL 自己那儿数的</b>（{@code SHOW GLOBAL STATUS}
 * 里的 {@code Com_select}），不是从缓存计数器推的。这样两组用的是**同一把尺子**：
 * 对照组压根不经过缓存，缓存计数器对它永远是 0，推不出任何东西；
 * 而 {@code Com_select} 对两组都成立——它数的就是"这次实验期间，数据库真的被执行了多少条
 * 查询语句"。
 *
 * <p><b>二、延迟是在真实 HTTP 上量的</b>（进程内起的 Tomcat + {@code TestRestTemplate}），
 * 不是直接调 Service。绝对值里因此含着 HTTP 那一层开销——但两组同样含，
 * 所以**对比仍然有效**，而且这样量到的才是"用户真正感受到的"那个延迟。
 *
 * <p><b>三、先预热再量</b>：第一轮里 JIT、连接池、Tomcat 线程池都在爬坡，
 * 把它们算进去只会让数字抖动。所以每一组都先跑一轮同样规模的、然后丢弃。
 */
final class DetailReadBenchmark {

    /** 热集大小：模拟"大家反复看的那一小撮帖子"。 */
    static final int HOT_SET = 20;

    /** 每组打多少次读。 */
    static final int REQUESTS = 2000;

    private DetailReadBenchmark() {
    }

    /**
     * 一次测量的读数。
     *
     * @param requests  打了多少次读
     * @param dbSelects 这期间 MySQL 真正执行了多少条 SELECT（含测量本身带来的 ±2 条）
     * @param loads     回源次数（缓存组才有意义）
     * @param l2Hits    L2 命中次数（缓存组才有意义）
     * @param l1Hits    L1 命中次数（Caffeine 自己数的）
     * @param p50Micros 一半的请求快于这个值
     * @param p99Micros 99% 的请求快于这个值
     */
    record Result(int requests, long dbSelects, long loads, long l2Hits, long l1Hits,
                  long p50Micros, long p99Micros) {

        double percent(long part) {
            return 100.0 * part / requests;
        }

        String row(String label) {
            return String.format("%-12s | %6d | %6d | %6.1f%% | %6.1f%% | %6.1f%% | %8.2f | %8.2f",
                    label, requests, dbSelects,
                    percent(l1Hits), percent(l2Hits), percent(loads),
                    p50Micros / 1000.0, p99Micros / 1000.0);
        }
    }

    static String header() {
        return String.format("%-12s | %6s | %6s | %7s | %7s | %7s | %8s | %8s",
                "组", "请求数", "查库数", "L1命中", "L2命中", "回源率", "P50(ms)", "P99(ms)");
    }

    /**
     * 打一轮流量并读数。
     *
     * @param invalidateL1EveryRequest {@code true} 时每次请求前都把这条帖子的 L1 清掉——
     *                                 用来单独考察**L2 那一层**的承载能力
     *                                 （模拟"进程刚重启，L1 全空"）。
     *                                 正常实验传 {@code false}
     */
    static Result measure(String label, TestRestTemplate rest, JdbcTemplate jdbc,
                          TwoLevelCache cache, LocalCache localCache, StringRedisTemplate redis,
                          List<Long> ids, int requests, boolean invalidateL1EveryRequest) {

        // 预热：丢掉数字，只让 JIT、连接池与 Tomcat 线程池进入状态
        for (int i = 0; i < 100; i++) {
            rest.getForEntity("/api/posts/" + ids.get(i % ids.size()), String.class);
        }

        // 预热会把这些帖子灌进两级缓存——**必须在预热之后、计数之前清掉**，
        // 否则正式那一轮读到的全是热缓存，量出来的"回源次数"是 0，
        // 而闸门要的恰恰是"从完全没有缓存开始"（设计 §9.2 第 1 步）。
        for (Long id : ids) {
            String key = RedisKey.cachePostDetail(id);
            redis.delete(key);
            localCache.invalidate(key);
        }

        CacheStats l1Before = localCache.stats();
        cache.resetLoadCount();
        cache.resetL2HitCount();
        long selectsBefore = comSelect(jdbc);

        long[] micros = new long[requests];
        for (int i = 0; i < requests; i++) {
            Long id = ids.get(i % ids.size());
            if (invalidateL1EveryRequest) {
                localCache.invalidate(RedisKey.cachePostDetail(id));
            }
            long t0 = System.nanoTime();
            ResponseEntity<String> response = rest.getForEntity("/api/posts/" + id, String.class);
            micros[i] = (System.nanoTime() - t0) / 1000;
            if (response.getStatusCode().value() != 200) {
                throw new AssertionError(
                        "详情接口没返回 200，本次读数作废：" + response.getStatusCode()
                                + " / " + response.getBody());
            }
        }

        long dbSelects = comSelect(jdbc) - selectsBefore;
        CacheStats l1After = localCache.stats();

        return new Result(requests, dbSelects, cache.loadCount(), cache.l2HitCount(),
                l1After.hitCount() - l1Before.hitCount(),
                percentile(micros, 0.50), percentile(micros, 0.99));
    }

    /**
     * MySQL 自己记的"执行过多少条 SELECT"。
     *
     * <p>注意 {@code SHOW GLOBAL STATUS} 本身也算一条 SELECT，所以一次测量会自带
     * 两三条的水分——相对两千次请求可以忽略，文档里如实写明。
     */
    private static long comSelect(JdbcTemplate jdbc) {
        Long value = jdbc.queryForObject("SHOW GLOBAL STATUS LIKE 'Com_select'",
                (rs, rowNum) -> rs.getLong("Value"));
        return value == null ? 0L : value;
    }

    /** 取分位数。{@code micros} 会被就地排序（调用方之后不再用它）。 */
    private static long percentile(long[] micros, double fraction) {
        long[] sorted = Arrays.copyOf(micros, micros.length);
        Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, (int) Math.floor(sorted.length * fraction));
        return sorted[index];
    }

    /** 把一组的读数打进控制台——集成测试的输出就是这份实验的原始记录。 */
    static void print(String label, Result result) {
        System.out.println(header());
        System.out.println(result.row(label));
    }
}
