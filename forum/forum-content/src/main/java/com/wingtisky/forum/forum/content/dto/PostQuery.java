package com.wingtisky.forum.forum.content.dto;

/**
 * 帖子列表的查询条件。
 *
 * <p><b>分页参数的夹紧在这里做，不在 Controller 里。</b>
 * 理由：Service 可能被别的地方调用（M3 的缓存预热、M10 的压测脚本），
 * 把校验放在 Controller 就等于"只有走 HTTP 的那条路才安全"。
 * 在数据结构的构造处夹紧，**无论谁构造它都安全**。
 *
 * <p>上限存在的理由很直接：不加的话，一个 {@code size=100000} 的请求
 * 就是一次免费的资源消耗——而它看起来完全合法。
 *
 * @param page 页码，**从 1 开始**。小于 1 会被夹到 1
 * @param size 每页条数。超出 {@link #MAX_SIZE} 会被夹到上限，小于 1 会被夹到 1
 * @param sort 排序方式。传 {@code null} 时按最新排
 */
public record PostQuery(int page, int size, PostSort sort) {

    /** 每页最多 50 条。 */
    public static final int MAX_SIZE = 50;

    public PostQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
        sort = sort == null ? PostSort.LATEST : sort;
    }

    /** 供 SQL 的 {@code LIMIT ? OFFSET ?} 用。 */
    public int offset() {
        return (page - 1) * size;
    }
}
