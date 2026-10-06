package com.wingtisky.forum.forum.content.mapper;

import com.wingtisky.forum.forum.content.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子表的读写。SQL 全部写在 {@code resources/mapper/PostMapper.xml} 里。
 *
 * <p><b>⚠️ 这里的方法收的是一个个标量参数，不是 {@code PostQuery} 那样的 record。</b>
 * 原因具体：MyBatis 解析 {@code #{sort}} 时，是在参数对象上找 {@code getSort()} 或
 * {@code isSort()} 这样的方法；而 record 生成的访问器叫 {@code sort()}，
 * **名字对不上**，会报 "There is no getter for property named 'sort'"。
 * 与其依赖某个 MyBatis 版本是否支持 record 参数（这是个需要查证的点），
 * 不如直接传标量——它一定对。
 */
@Mapper
public interface PostMapper {

    /**
     * 插入帖子。主键回填到 {@code post.id}（XML 里配了 {@code useGeneratedKeys}），
     * 调用方紧接着要用它返回给前端。
     */
    int insert(Post post);

    /** 查完整的一条（**含正文**）。详情页用。 */
    Post selectById(Long id);

    /**
     * 取一页列表项。**不查正文列**（见 {@code PostMapper.xml} 的 {@code List_Columns}）。
     *
     * @param authorId **为 {@code null} 表示不按作者筛**（首页）。传了值就是
     *                 "某人的帖子"（个人主页）。用同一个方法加可选条件，
     *                 是为了让"筛选条件"和"总数统计"共用同一段 SQL（见 XML 的
     *                 {@code List_Filter}）——两份各写一遍，迟早会不一致，
     *                 表现为"总数说有 100 条，翻到第 3 页就空了"
     * @param tagId    **为 {@code null} 表示不按标签筛**。用 {@code EXISTS} 子查询
     *                 而不是 {@code JOIN}：JOIN 在"同时筛多个标签"时会让同一篇帖子
     *                 出现多行（列表重复、总数偏大），而 EXISTS 从写法上就没这个问题
     * @param sort     {@code "LATEST"} 或 {@code "HOT"}。
     *                 ⚠️ **它不会拼进 SQL**——XML 里用 {@code <choose>} 在两种写死的排法之间选，
     *                 所以外部传进来的值到不了 SQL 里（这是不用字符串拼 ORDER BY 的原因）
     * @param offset   跳过多少条，由 {@code PostQuery} 算好
     */
    List<Post> selectPage(@Param("authorId") Long authorId,
                          @Param("tagId") Long tagId,
                          @Param("sort") String sort,
                          @Param("offset") int offset,
                          @Param("size") int size);

    /**
     * 与 {@link #selectPage} **同一套筛选条件**下的总数。分页要显示"共 N 条"。
     *
     * @param authorId 同 {@link #selectPage}，{@code null} 表示不按作者筛
     * @param tagId    同 {@link #selectPage}，{@code null} 表示不按标签筛
     */
    long countList(@Param("authorId") Long authorId, @Param("tagId") Long tagId);

    /**
     * 改标题与正文（**部分更新**：传 {@code null} 的字段不动）。摘要跟着正文走。
     *
     * <p><b>⚠️ 不要用返回的行数判断"帖子不存在"。</b>
     * MySQL 的 {@code UPDATE} 默认返回的是"**实际改变了的行数**"，
     * 不是"匹配到的行数"——所以把标题改成和原来一样的值，返回的是 0。
     * 拿它当"没找到"，就会出现"用户打开帖子、什么都没改、点保存 → 提示帖子不存在"。
     *
     * <p>调用方应先 {@link #selectById} 确认存在，再调用本方法。
     * （那个"返回值到底是改变数还是匹配数"还取决于 JDBC 驱动的 {@code useAffectedRows} 设置，
     * 与其赌它，不如不去依赖它。）
     */
    int updateContent(@Param("id") Long id,
                      @Param("title") String title,
                      @Param("content") String content,
                      @Param("summary") String summary);

    /**
     * 调整帖子的评论数。
     *
     * <p>发评论时 {@code delta = 1}，删评论时传**负数**（连带删掉的回复要一起算）。
     *
     * <p>用 {@code GREATEST(comment_count + ?, 0)} 而不是直接加：万一计数因为
     * 某次异常偏小了，减法会把评论数变成负数——**负数出现在界面上比数字偏小更难解释**。
     * 这是单条语句，并发安全。
     */
    int addCommentCount(@Param("postId") Long postId, @Param("delta") int delta);

    /**
     * 调整帖子的点赞数。含义与 {@link #addCommentCount} 相同，
     * 只是改的是另一列——**列名不能当参数传**（那要拼 SQL，等于开一个注入口子），
     * 所以每个计数各有一个方法。代价是同形状的方法会随计数种类增加。
     */
    int addLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    /** 调整帖子的收藏数。 */
    int addCollectCount(@Param("postId") Long postId, @Param("delta") int delta);

    /**
     * 后台治理：改置顶 / 加精 / 上下架（**部分更新**，传 {@code null} 的不动）。
     *
     * <p>{@code status} 与 {@code deleted} 是两件事，别混：前者是版主的治理动作
     * （可恢复），后者是作者删帖。这个方法的 {@code WHERE} 只排除 {@code deleted = 1}，
     * 不排除已下架的——**否则被下架的帖子就再也恢复不了了**。
     */
    int updateAttributes(@Param("id") Long id,
                         @Param("topFlag") Boolean topFlag,
                         @Param("featuredFlag") Boolean featuredFlag,
                         @Param("status") Integer status);

    /** 逻辑删除（标记 {@code deleted = 1}）。**不是真的删行**——见 V2 建表脚本的说明。 */
    int softDelete(Long id);

    /**
     * 只取浏览数这一列。
     *
     * <p><b>它只在"Redis 里的计数器不存在"时才被调到</b>（Redis 重启、键过期、
     * 或这篇帖子第一次被人看）。那时需要从库里拿一个基准值去给它播种。
     * 正常情况下每次读详情**不会**查它——这正是把浏览数搬去 Redis 的收益所在。
     *
     * @return {@code null} 表示这条帖子不存在或已删除
     */
    Integer selectViewCount(Long id);

    /**
     * 把浏览数**写成指定值**（不是 +1）。定时回写用。
     *
     * <p>写绝对值而不是增量，是为了让回写**幂等**：同一次回写重复执行，结果一样。
     * 增量的话，"写进去了但没来得及标记完成"会导致重试时又加一遍。
     */
    int updateViewCount(@Param("id") Long id, @Param("viewCount") int viewCount);
}
