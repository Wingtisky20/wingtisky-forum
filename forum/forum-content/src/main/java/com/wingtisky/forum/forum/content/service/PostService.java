package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.domain.user.UserQueryService;
import com.wingtisky.forum.forum.content.dto.PageResult;
import com.wingtisky.forum.forum.content.dto.PostDetail;
import com.wingtisky.forum.forum.content.dto.PostListItem;
import com.wingtisky.forum.forum.content.dto.PostQuery;
import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子的业务规则。
 *
 * <p><b>这里不放鉴权。</b>"能不能改这条帖子"由接口层的
 * {@code @PreAuthorize("@authz.isOwner(#id)")} 回答（M1 设计稿 §4.2）——
 * 业务方法只管"这件事怎么做"，不管"谁有资格做"。
 * 混在一起的话，将来多一个调用入口（比如定时任务、内部接口）就会绕过鉴权，
 * 而这种遗漏不会报错。
 *
 * <p><b>作者信息是通过 {@link UserQueryService} 拿的，不是直接查用户表。</b>
 * 这是 ADR-0015 定的跨域约定，由 {@code ArchitectureTest} 的规则 2 强制。
 */
@Service
public class PostService {

    /**
     * 摘要最多保留多少个**码点**。
     *
     * <p>用码点而不是 {@code String.length()}（那是 char 个数）：一个 emoji 在 Java 里
     * 占两个 char。按 char 截断会把最后一个 emoji 切成半个——存进库里就是非法字符，
     * 显示出来是乱码或问号，而且**不会报错**。
     * （{@code t_post.summary} 是 varchar(255)，按字符计，200 个字符放得下。）
     */
    static final int SUMMARY_MAX_CODE_POINTS = 200;

    private final PostMapper postMapper;
    private final UserQueryService userQueryService;

    public PostService(PostMapper postMapper, UserQueryService userQueryService) {
        this.postMapper = postMapper;
        this.userQueryService = userQueryService;
    }

    /**
     * 发帖。返回新帖子的 ID。
     *
     * <p>摘要在这里生成一次并落库，**列表页因此永远不用碰正文列**（设计稿 §2.6）。
     * 这和"计数冗余存表"是同一个思路：把代价花在写得少的那一侧。
     */
    public Long create(Long authorId, String title, String content) {
        Post post = new Post();
        post.setAuthorId(authorId);
        post.setTitle(title);
        post.setContent(content);
        post.setSummary(summarize(content));

        postMapper.insert(post);
        // insert 的 XML 配了 useGeneratedKeys，主键已经回填到 post 上
        return post.getId();
    }

    /**
     * 取一页帖子。
     *
     * <p>两处顺序上的讲究：
     * <ol>
     *   <li><b>先 COUNT，为 0 就直接返回</b>——一条都没有时连列表查询都不发，
     *       顺带也就不会去查作者（空集合的批量查询会拼出非法的 {@code IN ()}）</li>
     *   <li><b>先取完这一页，再一次性查作者</b>——而不是每篇查一次。
     *       后者就是 N+1：不报错，只在数据多起来之后表现为"列表越来越慢"</li>
     * </ol>
     */
    public PageResult<PostListItem> page(PostQuery query) {
        long total = postMapper.countPublished();
        if (total == 0) {
            return PageResult.empty(query.page(), query.size());
        }

        List<Post> posts = postMapper.selectPage(query.sort().name(), query.offset(), query.size());
        Map<Long, UserBrief> authors = findAuthors(posts);

        List<PostListItem> items = posts.stream()
                .map(post -> PostListItem.from(post, authors.get(post.getAuthorId())))
                .toList();
        return new PageResult<>(items, total, query.page(), query.size());
    }

    /**
     * 取详情。
     *
     * <p><b>这是个"读接口里带写"的方法</b>：它会给浏览数 +1。
     * 这是有意接受的——M2 先用最直接的方式把数据记下来，
     * **M3 会把它换成 Redis 累加 + 定期回写**，那时只改这一行。
     */
    public PostDetail getDetail(Long id) {
        Post post = postMapper.selectById(id);
        if (post == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }

        postMapper.incrementViewCount(id);

        // 返回值里也把本次访问算上：否则响应里的数字和数据库里的差 1，
        // 排查时会怀疑"更新是不是没生效"——把时间花在一个其实对的地方。
        post.setViewCount(post.getViewCount() + 1);

        UserBrief author = userQueryService.findBrief(post.getAuthorId()).orElse(null);
        return PostDetail.from(post, author);
    }

    /**
     * 改标题与正文。
     *
     * <p>用"受影响行数为 0"判断帖子不存在，**不必先查一次再改一次**。
     * 摘要跟着正文一起更新——它是从正文派生的，不同步就会出现
     * "列表里显示的是旧内容"这种很难一眼看出的错。
     */
    public void update(Long id, String title, String content) {
        int affected = postMapper.updateContent(id, title, content, summarize(content));
        if (affected == 0) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
    }

    /**
     * 删帖。**是标记删除，不是真的删行**——历史数据（评论、点赞）都指着它，
     * 真删了它们就变成孤儿（V2 建表脚本里有说明）。
     */
    public void delete(Long id) {
        int affected = postMapper.softDelete(id);
        if (affected == 0) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
    }

    /**
     * 一次把本页所有作者查回来。
     *
     * <p><b>不要改成循环里一个个查。</b>那是 N+1，而且不会报错——
     * 只会在帖子多起来之后表现为"首页越加载越慢"，到那时也很难一眼看出是这里。
     */
    private Map<Long, UserBrief> findAuthors(List<Post> posts) {
        Set<Long> authorIds = posts.stream()
                .map(Post::getAuthorId)
                .collect(Collectors.toSet());
        return userQueryService.findBriefs(authorIds);
    }

    /**
     * 从正文生成列表页摘要：先把它压成一整行，再按码点截断。
     *
     * <p>压成一行是因为列表页只有一两行的位置，正文里的换行会把卡片撑变形。
     */
    static String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        if (flat.codePointCount(0, flat.length()) <= SUMMARY_MAX_CODE_POINTS) {
            return flat;
        }
        return flat.substring(0, flat.offsetByCodePoints(0, SUMMARY_MAX_CODE_POINTS));
    }
}
