package com.wingtisky.forum.forum.content.dto;

import java.util.List;

/**
 * 一页数据。
 *
 * <p><b>带上 {@code total} 是因为前端要显示"共 N 条"并画出页码</b>——
 * 只给当前页的数据，前端就不知道后面还有没有内容。
 * 代价是每次列表要跑两条查询（一条 COUNT、一条取数据），这是分页的标准开销。
 *
 * @param items 当前页的数据，**可能为空列表，不会是 null**
 * @param total 满足条件的总条数（不是当前页条数）
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 * @param <T>   列表项类型
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    /** 空页。用于"一条都没有"时提前返回，省掉一次查询。 */
    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0, page, size);
    }
}
