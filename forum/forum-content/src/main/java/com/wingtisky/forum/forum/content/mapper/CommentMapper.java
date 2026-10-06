package com.wingtisky.forum.forum.content.mapper;

import com.wingtisky.forum.forum.content.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 评论表的读写。SQL 全部在 {@code resources/mapper/CommentMapper.xml}。
 *
 * <p><b>两级模型带来的两条查询路径</b>（ADR-0018）：
 * <ul>
 *   <li>顶层评论**分页**取（{@link #selectTopLevelPage}）——所以 {@code LIMIT} 只作用在它上面</li>
 *   <li>若干顶层评论的回复，**一次全部取回**（{@link #selectRepliesByRootIds}）——
 *       不是"每条顶层评论查一次"。后者是 N+1，而它不报错，
 *       只在一条热帖有上百条评论时表现为"评论页越来越慢"</li>
 * </ul>
 *
 * <p>参数用标量而不是 record，理由同 {@code PostMapper}——MyBatis 找的是
 * {@code getXxx()}，而 record 的访问器叫 {@code xxx()}。
 */
@Mapper
public interface CommentMapper {

    /** 插入评论，主键回填到 {@code comment.id}。 */
    int insert(Comment comment);

    Comment selectById(Long id);

    /**
     * 取一页**顶层评论**（{@code root_id = 0}）。最新的排前面。
     *
     * @param offset 跳过多少条，调用方算好
     */
    List<Comment> selectTopLevelPage(@Param("postId") Long postId,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    /** 该帖子下顶层评论的总数（分页要显示"共 N 条"）。 */
    long countTopLevel(Long postId);

    /**
     * 一次取回这些顶层评论下的**全部回复**（不分页）。
     *
     * <p><b>调用方必须保证集合非空</b>：{@code IN ()} 是非法 SQL，而它只在
     * "这一页恰好没有顶层评论"时才会炸——比如查一个超出范围的页码。
     * {@code CommentService} 已短路。
     *
     * <p>回复按 {@code id} **升序**（先发的在前）：一个讨论串顺着读才通顺，
     * 和顶层评论按最新排是两种不同的意图。
     */
    List<Comment> selectRepliesByRootIds(@Param("rootIds") Collection<Long> rootIds);

    /** 逻辑删除一条评论。返回受影响行数（软删除 0→1 必然改变，所以这个行数是可信的）。 */
    int softDelete(Long id);

    /**
     * 逻辑删除某条顶层评论下的**全部回复**（连带删除）。
     *
     * <p>返回删掉了几条——调用方拿它去减帖子的评论数，不必再单独查一次计数。
     */
    int softDeleteReplies(Long rootId);
}
