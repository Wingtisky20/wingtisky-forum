package com.wingtisky.forum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门实验 · <b>对照组「纯 MySQL」</b>（spec §6.2 的验收闸门，设计 §9.1）。
 *
 * <p>它与 {@link PostDetailCacheBenchmarkTest} **是同一份代码**，
 * 唯一的差别是下面这行属性：
 *
 * <pre>{@code properties = "wt.cache.post-detail.enabled=false"}</pre>
 *
 * <p><b>为什么必须靠开关、而不是"缓存上线前的那份代码"</b>：拿改前改后的两份代码比，
 * 差值里混着"期间还重构过什么"的影响，说明不了缓存的作用（设计 §9.1 原话）。
 * 用开关，两组之间**只有一个变量**。
 *
 * <p>这条也顺带是 Task 9 那个开关的最终验收：如果它其实没生效，这一组测出来的
 * 数字会和实验组**一模一样**——而那种"实验结论是假的"不会有任何报错，
 * 只会得到两张看起来很像真的表格。所以本类下面有一条断言专门钉死这件事：
 * 缓存那三个计数器**必须全是 0**。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wt.cache.post-detail.enabled=false",   // ← 唯一的变量
                "wt.rate-limit.enabled=false"           // 理由同实验组：限流与本次实验无关
        })
@ActiveProfiles("test")
@Tag("integration")
class PostDetailCacheControlGroupTest extends DetailReadBenchmarkSupport {

    @Test
    @DisplayName("★ 对照组：同样的 2000 次读，每一次都真的落到数据库上")
    void everyReadHitsTheDatabase() {
        DetailReadBenchmark.Result result = DetailReadBenchmark.measure(
                "纯 MySQL", rest, jdbc, cache, localCache, redis,
                hotIds, DetailReadBenchmark.REQUESTS, false);
        DetailReadBenchmark.print("纯 MySQL", result);

        assertThat(result.loads())
                .as("开关关掉时缓存这一层完全不参与——回源计数器必须是 0")
                .isZero();
        assertThat(result.l1Hits())
                .as("L1 也不该被碰过")
                .isZero();
        assertThat(result.l2Hits())
                .as("L2 也不该被碰过")
                .isZero();
        assertThat(result.dbSelects())
                .as("这一组的意义就在于：每一次读都真的压到了数据库上。"
                        + "数字明显小于请求数的话，说明「关掉缓存」这件事没有生效，"
                        + "而实验组那张表也就跟着失去意义")
                .isGreaterThan(DetailReadBenchmark.REQUESTS);
    }
}
