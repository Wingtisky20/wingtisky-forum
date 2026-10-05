package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.domain.user.UserQueryService;
import com.wingtisky.forum.forum.content.dto.PageResult;
import com.wingtisky.forum.forum.content.dto.PostDetail;
import com.wingtisky.forum.forum.content.dto.PostListItem;
import com.wingtisky.forum.forum.content.dto.PostQuery;
import com.wingtisky.forum.forum.content.dto.PostSort;
import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PostService} 的单元测试。
 *
 * <p>用 Mockito 替换 Mapper 与跨域契约，**不连数据库、不起 Spring**。
 * 这里要守的是"**不会报错、只会悄悄做错**"的那几件事：
 * 摘要截断会不会切坏字符、一页 20 个作者会不会被查 20 次、
 * 改正文时摘要会不会忘记同步。
 *
 * <p>SQL 本身对不对（比如 {@code UPDATE SET deleted = 1}）由集成测试覆盖——
 * 那一层连真实数据库，只在本地跑（ADR-0007）。
 */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private PostMapper postMapper;

    @Mock
    private UserQueryService userQueryService;

    private PostService postService;

    @BeforeEach
    void setUp() {
        postService = new PostService(postMapper, userQueryService);
    }

    private static Post post(long id, long authorId, String title) {
        Post p = new Post();
        p.setId(id);
        p.setAuthorId(authorId);
        p.setTitle(title);
        p.setSummary("摘要");
        p.setTopFlag(0);
        p.setFeaturedFlag(0);
        p.setViewCount(0);
        p.setLikeCount(0);
        p.setCommentCount(0);
        p.setCollectCount(0);
        p.setCreateTime(LocalDateTime.of(2026, 10, 5, 12, 0));
        return p;
    }

    private static BizException errorCodeOf(Throwable e) {
        return (BizException) e;
    }

    @Nested
    @DisplayName("发帖")
    class Create {

        @Test
        @DisplayName("摘要从正文生成：换行与连续空白压成一个空格")
        void shouldBuildSummaryFromContent() {
            postService.create(1L, "标题", "第一行\n\n第二行    带空格");

            ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
            verify(postMapper).insert(captor.capture());
            assertThat(captor.getValue().getSummary()).isEqualTo("第一行 第二行 带空格");
        }

        @Test
        @DisplayName("超长正文按 200 个码点截断")
        void shouldTruncateTo200CodePoints() {
            postService.create(1L, "标题", "文".repeat(500));

            String summary = capturedSummary();
            assertThat(summary.codePointCount(0, summary.length())).isEqualTo(200);
        }

        @Test
        @DisplayName("截断不会把 emoji 切成半个（按码点而不是按 char）")
        void shouldNotSplitEmoji() {
            // 199 个汉字 + 1 个 emoji：emoji 正好是第 200 个**码点**。
            // 按码点截 → 完整保留 emoji；按 char 截（substring(0,200)）→ 只留下它的高位代理字符。
            postService.create(1L, "标题", "文".repeat(199) + "😀" + "文".repeat(50));

            String summary = capturedSummary();

            assertThat(summary.codePointCount(0, summary.length())).isEqualTo(200);
            assertThat(summary).endsWith("😀");
            assertThat(Character.isHighSurrogate(summary.charAt(summary.length() - 1)))
                    .as("末尾不该是一个孤立的高位代理字符，那说明 emoji 被切成了半个")
                    .isFalse();
        }

        @Test
        @DisplayName("正文为空白时摘要为空串，不是 null")
        void shouldReturnEmptySummaryForBlankContent() {
            postService.create(1L, "标题", "   \n  ");

            assertThat(capturedSummary()).isEmpty();
        }

        private String capturedSummary() {
            ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
            verify(postMapper).insert(captor.capture());
            return captor.getValue().getSummary();
        }
    }

    @Nested
    @DisplayName("列表")
    class Page {

        @Test
        @DisplayName("一页的作者一次批量查回来——不是每篇查一次")
        void shouldLoadAuthorsInOneBatch() {
            when(postMapper.countPublished()).thenReturn(2L);
            when(postMapper.selectPage("LATEST", 0, 20)).thenReturn(List.of(
                    post(1L, 10L, "帖子一"),
                    post(2L, 20L, "帖子二")));
            when(userQueryService.findBriefs(Set.of(10L, 20L))).thenReturn(Map.of(
                    10L, new UserBrief(10L, "甲", null),
                    20L, new UserBrief(20L, "乙", "http://a/2.png")));

            PageResult<PostListItem> result = postService.page(new PostQuery(1, 20, PostSort.LATEST));

            assertThat(result.total()).isEqualTo(2);
            assertThat(result.items()).hasSize(2);
            assertThat(result.items().get(0).author().nickname()).isEqualTo("甲");
            assertThat(result.items().get(1).author().avatar()).isEqualTo("http://a/2.png");

            // 关键断言：**只查了一次**，且参数正好是本页那两个作者。
            // 写成循环单查也能让上面几行通过，只有这一行能抓住 N+1。
            verify(userQueryService, times(1)).findBriefs(Set.of(10L, 20L));
        }

        @Test
        @DisplayName("一条帖子都没有时，连列表查询和作者查询都不发")
        void shouldShortCircuitWhenTotalIsZero() {
            when(postMapper.countPublished()).thenReturn(0L);

            PageResult<PostListItem> result = postService.page(new PostQuery(1, 20, PostSort.LATEST));

            assertThat(result.items()).isEmpty();
            assertThat(result.total()).isZero();
            verify(postMapper, never()).selectPage(anyString(), anyInt(), anyInt());
            // 顺带避开一个坑：空集合的批量查询会拼出非法的 IN ()
            verifyNoInteractions(userQueryService);
        }

        @Test
        @DisplayName("作者已注销时 author 为 null，但这个条目仍然在列表里")
        void shouldKeepItemWhenAuthorIsGone() {
            when(postMapper.countPublished()).thenReturn(1L);
            when(postMapper.selectPage(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of(post(1L, 10L, "帖子")));
            when(userQueryService.findBriefs(any())).thenReturn(Map.of());

            PageResult<PostListItem> result = postService.page(new PostQuery(1, 20, PostSort.LATEST));

            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).author()).isNull();
            assertThat(result.items().get(0).authorId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("排序参数原样传给 Mapper（真正的排法写死在 XML 里）")
        void shouldPassSortToMapper() {
            when(postMapper.countPublished()).thenReturn(1L);
            when(postMapper.selectPage("HOT", 0, 20)).thenReturn(List.of());
            when(userQueryService.findBriefs(any())).thenReturn(Map.of());

            postService.page(new PostQuery(1, 20, PostSort.HOT));

            verify(postMapper).selectPage("HOT", 0, 20);
        }
    }

    @Nested
    @DisplayName("详情")
    class Detail {

        @Test
        @DisplayName("给浏览数 +1，且返回的数字把本次访问算上")
        void shouldIncrementViewCount() {
            Post p = post(1L, 10L, "标题");
            p.setContent("正文");
            p.setViewCount(7);
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(10L))
                    .thenReturn(Optional.of(new UserBrief(10L, "甲", null)));

            PostDetail detail = postService.getDetail(1L);

            verify(postMapper).incrementViewCount(1L);
            assertThat(detail.viewCount()).as("响应里的数字要和数据库里的一致").isEqualTo(8);
            assertThat(detail.content()).isEqualTo("正文");
            assertThat(detail.author().nickname()).isEqualTo("甲");
        }

        @Test
        @DisplayName("帖子不存在 → A0201，且不会去动浏览数")
        void shouldThrowWhenPostMissing() {
            when(postMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> postService.getDetail(999L))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));

            verify(postMapper, never()).incrementViewCount(anyLong());
        }
    }

    @Nested
    @DisplayName("改帖与删帖")
    class UpdateAndDelete {

        @Test
        @DisplayName("改帖时摘要跟着正文一起更新")
        void shouldUpdateSummaryTogetherWithContent() {
            when(postMapper.updateContent(eq(1L), eq("新标题"), eq("新正文"), anyString()))
                    .thenReturn(1);

            postService.update(1L, "新标题", "新正文");

            ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
            verify(postMapper).updateContent(eq(1L), eq("新标题"), eq("新正文"), summary.capture());
            // 不一起更新的话，列表页会一直显示旧摘要——很难一眼看出是哪里错了
            assertThat(summary.getValue()).isEqualTo("新正文");
        }

        @Test
        @DisplayName("改一条不存在的帖子 → A0201")
        void shouldThrowWhenUpdatingMissingPost() {
            when(postMapper.updateContent(anyLong(), anyString(), anyString(), anyString()))
                    .thenReturn(0);

            assertThatThrownBy(() -> postService.update(999L, "t", "c"))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("删帖走的是标记删除方法（真正的 UPDATE SET deleted = 1 在 XML 里，集成测试覆盖）")
        void shouldCallSoftDelete() {
            when(postMapper.softDelete(1L)).thenReturn(1);

            postService.delete(1L);

            verify(postMapper).softDelete(1L);
        }

        @Test
        @DisplayName("删一条不存在的帖子 → A0201")
        void shouldThrowWhenDeletingMissingPost() {
            when(postMapper.softDelete(999L)).thenReturn(0);

            assertThatThrownBy(() -> postService.delete(999L))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));
        }
    }
}
