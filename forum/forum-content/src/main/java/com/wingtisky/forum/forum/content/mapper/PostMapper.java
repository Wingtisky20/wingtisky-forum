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
     * @param sort   {@code "LATEST"} 或 {@code "HOT"}。
     *               ⚠️ **它不会拼进 SQL**——XML 里用 {@code <choose>} 在两种写死的排法之间选，
     *               所以外部传进来的值到不了 SQL 里（这是不用字符串拼 ORDER BY 的原因）
     * @param offset 跳过多少条，由 {@code PostQuery} 算好
     */
    List<Post> selectPage(@Param("sort") String sort,
                          @Param("offset") int offset,
                          @Param("size") int size);

    /** 当前"正常可见"的帖子总数（未删、未下架）。分页要显示"共 N 条"。 */
    long countPublished();

    /**
     * 改标题与正文。摘要一并更新——**它是由正文派生的**，
     * 改了正文却不改摘要，列表页就会一直显示旧内容。
     *
     * <p>返回受影响行数，**调用方用它判断"帖子不存在"**（0 行就是没找到），
     * 这样不必先查一次再改一次。
     */
    int updateContent(@Param("id") Long id,
                      @Param("title") String title,
                      @Param("content") String content,
                      @Param("summary") String summary);

    /** 逻辑删除（标记 {@code deleted = 1}）。**不是真的删行**——见 V2 建表脚本的说明。 */
    int softDelete(Long id);

    /**
     * 浏览数 +1。
     *
     * <p>用单条 {@code UPDATE ... SET view_count = view_count + 1} 而不是
     * "查出来、加一、写回去"——后者在并发下会丢更新（两个请求都读到 10，都写回 11）。
     *
     * <p><b>M3 会把这个方法换掉</b>：改成 Redis 累加、定时回写，
     * 因为"每次读详情都写一次库"在流量上来之后会成为瓶颈（设计稿 §2.1）。
     */
    int incrementViewCount(Long id);
}
