package com.wingtisky.forum.forum.content.mapper;

import com.wingtisky.forum.forum.content.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 标签表与"帖子-标签"关联表的读写。
 *
 * <p><b>没有 {@code PostTag} 实体</b>：关联表是两列的主键，没有自己的状态。
 * 为它建一个实体类只会多一个没人真正需要的类型——所有操作都用
 * {@code (postId, tagId)} 两个标量就够了。
 */
@Mapper
public interface TagMapper {

    /**
     * 按名字查标签。**大小写不敏感**（由数据库的排序规则决定，不是这里做了什么）——
     * 所以查 {@code Redis} 也会命中已经存在的 {@code redis}。
     */
    Tag selectByName(String name);

    /**
     * 插入新标签，主键回填。
     *
     * <p><b>调用方必须处理 {@code DuplicateKeyException}</b>：并发的两个请求
     * 同时第一次用同一个标签名时，唯一索引会拦下第二个。**这不是错误**——
     * 回头重查一次就能拿到别人建好的那个（见 {@code TagService.findOrCreate}）。
     */
    int insert(Tag tag);

    /** 热门标签：按使用次数倒序。 */
    List<Tag> selectTop(@Param("limit") int limit);

    /**
     * 调整标签的帖子数。{@code delta} 可以是负数（删帖、改帖换标签）。
     * SQL 里有 {@code GREATEST(..., 0)} 做下限保护。
     */
    int addPostCount(@Param("tagId") Long tagId, @Param("delta") int delta);

    /**
     * 建立帖子与标签的关联。
     *
     * <p><b>调用方必须保证这一对是新的</b>：联合主键会拦下重复插入。
     * 正常情况下不会重复（改帖时先清空再建立），但如果调用方漏了"先清空"，
     * 表现会是一条主键冲突异常——**它长得不像"标签重复"，排查时容易跑偏**。
     */
    int insertPostTag(@Param("postId") Long postId, @Param("tagId") Long tagId);

    /** 这条帖子当前带的标签（改帖 / 删帖时要把它们的计数减回去）。 */
    List<Tag> selectByPostId(Long postId);

    /** 删掉这条帖子的全部标签关联（改帖换标签、删帖时用）。 */
    int deletePostTags(Long postId);
}
