package com.wingtisky.forum.forum.content.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostQuery} 的参数夹紧。
 *
 * <p>夹紧放在这个 record 里而不是 Controller 里，所以它可以被单独测——
 * Service 将来被别的地方调用（M3 的缓存预热、M10 的压测脚本）时同样安全。
 */
class PostQueryTest {

    @Test
    @DisplayName("页码小于 1 时夹到 1")
    void shouldClampPageToAtLeastOne() {
        assertThat(new PostQuery(0, 20, PostSort.LATEST).page()).isEqualTo(1);
        assertThat(new PostQuery(-5, 20, PostSort.LATEST).page()).isEqualTo(1);
    }

    @Test
    @DisplayName("每页条数超过 50 时夹到 50")
    void shouldClampSizeToMax() {
        // 不夹的话，size=100000 就是一个完全合法、却能让数据库翻很久的请求
        assertThat(new PostQuery(1, 100_000, PostSort.LATEST).size()).isEqualTo(PostQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("每页条数小于 1 时夹到 1")
    void shouldClampSizeToAtLeastOne() {
        assertThat(new PostQuery(1, 0, PostSort.LATEST).size()).isEqualTo(1);
        assertThat(new PostQuery(1, -1, PostSort.LATEST).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("排序传 null 时默认按最新排")
    void shouldDefaultSortToLatest() {
        assertThat(new PostQuery(1, 20, null).sort()).isEqualTo(PostSort.LATEST);
    }

    @Test
    @DisplayName("offset 由页码算出来：第 1 页从 0 开始")
    void shouldComputeOffset() {
        assertThat(new PostQuery(1, 20, PostSort.LATEST).offset()).isZero();
        assertThat(new PostQuery(3, 20, PostSort.LATEST).offset()).isEqualTo(40);
    }
}
