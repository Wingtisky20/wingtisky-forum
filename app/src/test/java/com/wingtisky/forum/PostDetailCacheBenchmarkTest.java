package com.wingtisky.forum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门实验 · <b>实验组「三级缓存」</b>（spec §6.2 的验收闸门，设计 §9.1）。
 *
 * <p>对照组在 {@link PostDetailCacheControlGroupTest}：**同一份代码**，
 * 只把 {@code wt.cache.post-detail.enabled} 翻成 {@code false}。
 *
 * <p>它量三样东西，都是往 MySQL 自己那里问来的或者框架自己数的：
 * <ul>
 *   <li><b>数据库真的被读了多少次</b>——{@code SHOW GLOBAL STATUS} 的 {@code Com_select}。
 *       两组共用这一把尺子</li>
 *   <li><b>命中率</b>——L1 由 Caffeine 自己数（{@code recordStats()}），
 *       L2 由 {@code TwoLevelCache} 的打点计数</li>
 *   <li><b>P50 / P99 延迟</b>——在真实 HTTP 上量（进程内起的 Tomcat）</li>
 * </ul>
 *
 * <p>跑法（集成测试不进 CI，只在本地跑，ADR-0007）：
 * <pre>
 * mvn -B test -pl app -am -Dexcluded.groups=__none__ -Dgroups=integration \
 *     -Dtest=PostDetailCacheBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 打流量之前必须先关掉限流：读接口的额度是 100 次/分钟，
        // 而本实验是 2000 次——不关的话跑到第 101 次就开始收 429，
        // 量出来的"延迟"其实是限流器的拒绝延迟（实测踩过）。
        // `wt.rate-limit.enabled` 这个开关本身就是为压测准备的，见 RateLimitProperties 的类注释。
        properties = "wt.rate-limit.enabled=false")
@ActiveProfiles("test")
@Tag("integration")
class PostDetailCacheBenchmarkTest extends DetailReadBenchmarkSupport {

    @Test
    @DisplayName("★ 实验组：2000 次读，数据库只被碰了「热集那么大」那么多下")
    void threeLevelCacheKeepsDatabaseAlmostUntouched() {
        DetailReadBenchmark.Result result = DetailReadBenchmark.measure(
                "三级缓存", rest, jdbc, cache, localCache, redis,
                hotIds, DetailReadBenchmark.REQUESTS, false);
        DetailReadBenchmark.print("三级缓存", result);

        assertThat(result.loads())
                .as("整个实验里，每个热帖**只回源一次**——第一次读把它装进缓存，之后全由缓存供给")
                .isEqualTo(DetailReadBenchmark.HOT_SET);
        assertThat(result.dbSelects())
                .as("数据库被读的次数要远小于请求数，这正是这个亮点要拿到的收益")
                .isLessThan(DetailReadBenchmark.REQUESTS / 10);
        assertThat(result.dbSelects())
                .as("但也**不能是 0**——那说明这把尺子根本没量到东西，"
                        + "两组的对比就成了两个零比大小")
                .isGreaterThan(result.loads());
        assertThat(result.l1Hits() + result.l2Hits() + result.loads())
                .as("三种去向加起来应当正好等于请求数，不多不少")
                .isEqualTo(DetailReadBenchmark.REQUESTS);
    }

    @Test
    @DisplayName("★ 单独看 L2：每次都把 L1 清掉（模拟进程刚重启），这层的活全归它")
    void l2AloneCarriesTheLoad() {
        DetailReadBenchmark.Result result = DetailReadBenchmark.measure(
                "只有L2", rest, jdbc, cache, localCache, redis,
                hotIds, DetailReadBenchmark.REQUESTS, true);
        DetailReadBenchmark.print("只有 L2（每次清 L1）", result);

        // 不这么测的话，L2 的命中率在上一条里会是个接近 0 的数——
        // L1 在前面挡着，L2 只在 L1 未命中时才会被问到。那会让读数看起来
        // 像"L2 没用"，而事实是它**没机会表现**。
        assertThat(result.l2Hits())
                .as("L1 每次都被清掉，能挡住回源的只可能是 L2")
                .isGreaterThan(DetailReadBenchmark.REQUESTS * 9L / 10);
    }
}
