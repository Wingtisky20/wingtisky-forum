package com.wingtisky.forum.forum.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 帖子收藏的读写（表 {@code t_post_collect}）。
 *
 * <p>与 {@link PostLikeMapper} 结构几乎一样，理由也相同——见那边的类注释，
 * 这里不重复。两者的差别只有一个：收藏表多一个"按时间倒序看某人收藏了哪些帖子"
 * 的用途（{@code create_time} 就是为它留的）。
 */
@Mapper
public interface PostCollectMapper {

    /** 收藏。重复收藏不会报错；返回值是"真的插进去了吗"（1 是 / 0 否）。 */
    int insertIgnore(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 取消收藏。**物理删除**；返回值是删掉了几行。 */
    int delete(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 这个人收藏过这篇帖子吗。返回 0 或 1。 */
    int exists(@Param("userId") Long userId, @Param("postId") Long postId);
}
