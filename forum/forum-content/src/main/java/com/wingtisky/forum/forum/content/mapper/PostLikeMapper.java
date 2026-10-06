package com.wingtisky.forum.forum.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 帖子点赞的读写（表 {@code t_post_like}）。
 *
 * <p><b>没有对应的实体类</b>：这张表是 {@code (user_id, post_id)} 联合主键 +
 * 一个时间戳，行本身没有值得建模的状态。所有操作用两个标量就够了，
 * 建实体只会多一个没人真正需要的类型。
 *
 * <p><b>它没有 {@code deleted} 列</b>（全库只有点赞与收藏这两张表没有）：
 * "取消点赞"的语义是**这件事没发生过**，留一行标记删除没有意义。
 * 详见 {@code db/V2__init_content.sql} 里那段说明。
 *
 * <p>与 {@code PostCollectMapper} 结构几乎一样——这是 ADR-0017 有意选择的
 * （两张专用表，而不是一张通用表），代价就是这一层看起来重复。
 */
@Mapper
public interface PostLikeMapper {

    /**
     * 点赞。**重复点赞不会报错**——`INSERT IGNORE` 会在主键冲突时安静跳过。
     *
     * <p><b>返回值是"真的插进去了吗"</b>：插进去了返回 1，撞主键返回 0。
     * 调用方用它决定要不要给帖子的点赞数 +1 —— 不加这个判断的话，
     * 同一个人点十次，计数就是十。
     *
     * <p>为什么不用"先查有没有点过、没有就插"：那中间有窗口，两个并发请求
     * 都查到"没点过"，然后都插入（或一个报错）。让数据库的主键来保证，
     * 是这里唯一可靠的做法。
     */
    int insertIgnore(@Param("userId") Long userId, @Param("postId") Long postId);

    /**
     * 取消点赞。**物理删除**——不是标记删除。
     *
     * <p>返回值是删掉了几行：0 表示本来就没点过赞，此时不该去减计数
     * （减了就会把别人的赞算掉）。
     */
    int delete(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 这个人点过这篇帖子吗。返回 0 或 1。 */
    int exists(@Param("userId") Long userId, @Param("postId") Long postId);
}
