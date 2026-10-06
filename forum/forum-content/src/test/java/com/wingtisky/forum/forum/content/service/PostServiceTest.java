package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.domain.user.UserQueryService;
import com.wingtisky.forum.forum.content.cache.CachedPostDetail;
import com.wingtisky.forum.forum.content.cache.PostDetailCache;
import com.wingtisky.forum.forum.content.dto.PageResult;
import com.wingtisky.forum.forum.content.cache.PostViewCounter;
import com.wingtisky.forum.forum.content.dto.PostDetail;
import com.wingtisky.forum.forum.content.dto.PostListItem;
import com.wingtisky.forum.forum.content.dto.PostQuery;
import com.wingtisky.forum.forum.content.dto.PostSort;
import com.wingtisky.forum.forum.content.dto.TagView;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private TagService tagService;

    @Mock
    private InteractionService interactionService;

    /** 浏览数的计数器（M3 起它接手了"读接口里带写"那件事）。 */
    @Mock
    private PostViewCounter postViewCounter;

    /** 帖子详情的缓存。**策略层**——这里把机制那一段换成一个可控的替身。 */
    @Mock
    private PostDetailCache postDetailCache;

    private PostService postService;

    @BeforeEach
    void setUp() {
        postService = new PostService(postMapper, userQueryService, tagService, interactionService,
                postViewCounter, postDetailCache);

        // 默认把缓存演成"永远不命中"：老实调回源函数，再把它的结果原样返回。
        // 这样 M2 那一批只 mock 了 Mapper 的用例原样继续有效——它们描述的正是
        // **回源那条路**，不该因为上面多了一层缓存就失效。
        // "命中"由各用例自己 thenReturn 一个缓存对象来演。
        //
        // lenient：并非每个用例都会走到详情（发帖、列表、改帖都不走），
        // 严格模式会把"没用上的桩"当错误报出来。
        lenient().when(postDetailCache.get(anyLong(), any()))
                .thenAnswer(inv -> {
                    Supplier<?> loader = inv.getArgument(1);
                    // 必须挡 null：录制别的桩时（`when(postDetailCache.get(...))` 那一行），
                    // Mockito 传进来的占位参数就是 null，而这条桩已经生效了。
                    // 不挡的话，会在**写测试的那一行**炸出一个和被测代码毫无关系的 NPE。
                    return loader == null ? null : loader.get();
                });
    }

    private static Post post(long id, long authorId, String title) {
        Post p = new Post();
        p.setId(id);
        p.setAuthorId(authorId);
        p.setTitle(title);
        p.setSummary("摘要");
        // ⚠️ 必须填 status 与 deleted：真实的行里它们是 NOT NULL DEFAULT 0，
        // 而 Post.isVisible() 用的是"status 非 null 且为 0"——
        // 造数时漏掉这两项，会让一条本该可见的帖子变成"不可见"，
        // 表现是一堆本该通过的详情测试报"帖子不存在"。（已踩过一次。）
        p.setStatus(Post.STATUS_PUBLISHED);
        p.setDeleted(0);
        p.setTopFlag(0);
        p.setFeaturedFlag(0);
        p.setViewCount(0);
        p.setLikeCount(0);
        p.setCommentCount(0);
        p.setCollectCount(0);
        p.setCreateTime(LocalDateTime.of(2026, 10, 5, 12, 0));
        return p;
    }

    /**
     * 造一个"已经在缓存里"的详情——各用例用 {@code thenReturn} 它来演"命中缓存"。
     *
     * @param visible 这条帖子本身可不可见（未被下架、未被删除）。已下架的帖子
     *                也会进缓存（作者与版主还要打开它），所以这两个参数是分开的
     */
    private static CachedPostDetail cached(long id, long authorId, String title,
                                           boolean visible, boolean offline) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 5, 12, 0);
        return new CachedPostDetail(id, title, "正文", authorId,
                new UserBrief(authorId, "甲", null),
                visible, false, false, offline,
                2, 3, 4, List.of(new TagView(9L, "Redis", 1)), t, t);
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
            postService.create(1L, "标题", "第一行\n\n第二行    带空格", List.of());

            ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
            verify(postMapper).insert(captor.capture());
            assertThat(captor.getValue().getSummary()).isEqualTo("第一行 第二行 带空格");
        }

        @Test
        @DisplayName("超长正文按 200 个码点截断")
        void shouldTruncateTo200CodePoints() {
            postService.create(1L, "标题", "文".repeat(500), List.of());

            String summary = capturedSummary();
            assertThat(summary.codePointCount(0, summary.length())).isEqualTo(200);
        }

        @Test
        @DisplayName("截断不会把 emoji 切成半个（按码点而不是按 char）")
        void shouldNotSplitEmoji() {
            // 199 个汉字 + 1 个 emoji：emoji 正好是第 200 个**码点**。
            // 按码点截 → 完整保留 emoji；按 char 截（substring(0,200)）→ 只留下它的高位代理字符。
            postService.create(1L, "标题", "文".repeat(199) + "😀" + "文".repeat(50), List.of());

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
            postService.create(1L, "标题", "   \n  ", List.of());

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
            when(postMapper.countList(null, null)).thenReturn(2L);
            when(postMapper.selectPage(null, null, "LATEST", 0, 20)).thenReturn(List.of(
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
            when(postMapper.countList(null, null)).thenReturn(0L);

            PageResult<PostListItem> result = postService.page(new PostQuery(1, 20, PostSort.LATEST));

            assertThat(result.items()).isEmpty();
            assertThat(result.total()).isZero();
            verify(postMapper, never()).selectPage(any(), any(), anyString(), anyInt(), anyInt());
            // 顺带避开一个坑：空集合的批量查询会拼出非法的 IN ()
            verifyNoInteractions(userQueryService);
        }

        @Test
        @DisplayName("作者已注销时 author 为 null，但这个条目仍然在列表里")
        void shouldKeepItemWhenAuthorIsGone() {
            when(postMapper.countList(null, null)).thenReturn(1L);
            when(postMapper.selectPage(any(), any(), anyString(), anyInt(), anyInt()))
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
            when(postMapper.countList(null, null)).thenReturn(1L);
            when(postMapper.selectPage(null, null, "HOT", 0, 20)).thenReturn(List.of());
            when(userQueryService.findBriefs(any())).thenReturn(Map.of());

            postService.page(new PostQuery(1, 20, PostSort.HOT));

            verify(postMapper).selectPage(null, null, "HOT", 0, 20);
        }

        @Test
        @DisplayName("按作者筛：筛选条件传给 Mapper，总数也按同一条件算")
        void shouldFilterByAuthor() {
            when(postMapper.countList(10L, null)).thenReturn(1L);
            when(postMapper.selectPage(10L, null, "LATEST", 0, 20))
                    .thenReturn(List.of(post(1L, 10L, "他的帖子")));
            when(userQueryService.findBriefs(any())).thenReturn(Map.of());

            PageResult<PostListItem> result =
                    postService.pageByAuthor(10L, new PostQuery(1, 20, PostSort.LATEST));

            assertThat(result.total()).isEqualTo(1L);
            assertThat(result.items()).hasSize(1);
            // 取数据和数总数必须带同一个筛选条件——否就会出现
            // "总数说有 5 条、实际只翻出 1 条"这种自相矛盾的响应
            verify(postMapper).countList(10L, null);
            verify(postMapper).selectPage(10L, null, "LATEST", 0, 20);
        }

        @Test
        @DisplayName("作者 ID 为 null → A0101 参数不合法，且不查库")
        void shouldRejectNullAuthorId() {
            assertThatThrownBy(() -> postService.pageByAuthor(null, new PostQuery(1, 20, PostSort.LATEST)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.PARAM_INVALID));

            verifyNoInteractions(postMapper);
        }
    }

    @Nested
    @DisplayName("详情")
    class Detail {

        @Test
        @DisplayName("浏览数走 Redis 计数器；响应里的数字就是计数器返回的新值")
        void shouldCountViewThroughRedisCounter() {
            Post p = post(1L, 10L, "标题");
            p.setContent("正文");
            p.setViewCount(7);
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(10L))
                    .thenReturn(Optional.of(new UserBrief(10L, "甲", null)));
            // 计数器返回的就是"含本次访问"的新值。M2 时这一步靠手工 +1，
            // 所以那时才要在注释里解释"响应里的数字和库里差 1"——现在不需要了。
            when(postViewCounter.increment(eq(1L), any())).thenReturn(8L);

            PostDetail detail = postService.getDetail(1L, 7L, false);

            verify(postViewCounter).increment(eq(1L), any());
            // **这条才是重点**：读详情不该再写库。
            // 把浏览数搬去 Redis 的收益就是这一条，而它只有靠"断言没发生写"才守得住
            // ——"响应里的数字对了"证明不了没写库。
            verify(postMapper, never()).updateViewCount(anyLong(), anyInt());
            assertThat(detail.viewCount()).isEqualTo(8);
            assertThat(detail.content()).isEqualTo("正文");
            assertThat(detail.author().nickname()).isEqualTo("甲");
        }

        @Test
        @DisplayName("详情里带上『我点过赞/收藏没』——前端的按钮要靠它显示选中状态")
        void shouldReportViewerInteraction() {
            Post p = post(1L, 10L, "标题");
            p.setContent("正文");
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(10L)).thenReturn(Optional.empty());
            when(interactionService.liked(1L, 7L)).thenReturn(true);
            when(interactionService.collected(1L, 7L)).thenReturn(false);

            PostDetail detail = postService.getDetail(1L, 7L, false);

            assertThat(detail.liked()).isTrue();
            assertThat(detail.collected()).isFalse();
        }

        @Test
        @DisplayName("未登录看详情时，不去查『有没有点过赞』")
        void shouldNotQueryInteractionForAnonymousViewer() {
            Post p = post(1L, 10L, "标题");
            p.setContent("正文");
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(10L)).thenReturn(Optional.empty());

            PostDetail detail = postService.getDetail(1L, null, false);

            assertThat(detail.liked()).isFalse();
            assertThat(detail.collected()).isFalse();
            // 注意这里**不断言**"没调过 interactionService"——在这一层它是 mock，
            // 调用确实会发生。真正"不查库"的保证在 InteractionService 内部，
            // 由它自己的测试用 verifyNoInteractions(postLikeMapper) 覆盖。
            // （这条是我第一版写错了：把别人的职责当成这一层的断言。）
        }

        @Test
        @DisplayName("帖子不存在 → A0201，且不会去动浏览数")
        void shouldThrowWhenPostMissing() {
            when(postMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> postService.getDetail(999L, null, false))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));

            verify(postViewCounter, never()).increment(anyLong(), any());
        }

        // ---------- M3 Task 8：详情接上缓存 ----------

        @Test
        @DisplayName("★ 命中缓存时**一次库都不查**——缓存的意义全在这一条")
        void shouldNotTouchDatabaseWhenCacheHits() {
            when(postDetailCache.get(eq(1L), any()))
                    .thenReturn(cached(1L, 10L, "缓存里的标题", true, false));
            when(postViewCounter.increment(eq(1L), any())).thenReturn(42L);

            PostDetail detail = postService.getDetail(1L, null, false);

            assertThat(detail.title()).isEqualTo("缓存里的标题");
            assertThat(detail.tags()).hasSize(1);
            // 浏览数**不来自缓存**——缓存对象里压根没有这个字段，它由计数器给
            assertThat(detail.viewCount()).isEqualTo(42);

            // 下面三条才是重点。"响应里的字段都对"靠别的写法也能通过，
            // 只有"没查过库"能证明缓存真的生效了。
            verifyNoInteractions(postMapper);
            verifyNoInteractions(userQueryService);
            verifyNoInteractions(tagService);
        }

        @Test
        @DisplayName("未命中时回源：帖子、作者、标签一次取齐，组装成详情")
        void shouldAssembleEverythingOnCacheMiss() {
            Post p = post(1L, 10L, "库里的标题");
            p.setContent("库里的正文");
            p.setLikeCount(3);
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(10L))
                    .thenReturn(Optional.of(new UserBrief(10L, "甲", null)));
            List<TagView> tags = List.of(new TagView(9L, "Redis", 1));
            when(tagService.listByPost(1L)).thenReturn(tags);
            when(postViewCounter.increment(eq(1L), any())).thenReturn(1L);

            PostDetail detail = postService.getDetail(1L, null, false);

            assertThat(detail.title()).isEqualTo("库里的标题");
            assertThat(detail.content()).isEqualTo("库里的正文");
            assertThat(detail.likeCount()).isEqualTo(3);
            assertThat(detail.author().nickname()).isEqualTo("甲");
            assertThat(detail.tags()).isEqualTo(tags);
            verify(postDetailCache).get(eq(1L), any());
        }

        @Test
        @DisplayName("★★ 安全：缓存里那条**正是已下架**的帖子时，匿名读仍然是 404")
        void offlinePostInCacheIsStillHiddenFromAnonymous() {
            // 已下架的帖子**会**正常进缓存——作者与版主还要打开它。
            // 所以"缓存里有"绝不能等价于"谁都能看"：可见性必须在缓存之外重判，
            // 否则一篇被下架的帖子被缓存之后，匿名用户就能拿到正文了。
            when(postDetailCache.get(eq(1L), any()))
                    .thenReturn(cached(1L, 10L, "被下架的帖子", false, true));

            assertThatThrownBy(() -> postService.getDetail(1L, null, false))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));

            // 看不见的东西，连浏览数都不该记
            verify(postViewCounter, never()).increment(anyLong(), any());
        }

        @Test
        @DisplayName("★ 缓存里那条已下架的帖子，作者本人仍然打得开，且 offline 标记还在")
        void offlinePostInCacheIsVisibleToItsAuthor() {
            when(postDetailCache.get(eq(1L), any()))
                    .thenReturn(cached(1L, 10L, "被下架的帖子", false, true));
            when(postViewCounter.increment(eq(1L), any())).thenReturn(1L);

            PostDetail detail = postService.getDetail(1L, 10L, false);

            assertThat(detail.title()).isEqualTo("被下架的帖子");
            assertThat(detail.offline()).as("要告诉作者这篇被下架了").isTrue();
        }

        @Test
        @DisplayName("★ 缓存里那条已下架的帖子，版主也打得开")
        void offlinePostInCacheIsVisibleToModerator() {
            when(postDetailCache.get(eq(1L), any()))
                    .thenReturn(cached(1L, 10L, "被下架的帖子", false, true));
            when(postViewCounter.increment(eq(1L), any())).thenReturn(1L);

            assertThat(postService.getDetail(1L, 999L, true).title()).isEqualTo("被下架的帖子");
        }
    }

    @Nested
    @DisplayName("后台治理与可见性")
    class AdminAndVisibility {

        /** 造一条已下架的帖子（status = 1）。 */
        private Post offlinePost(long id, long authorId) {
            Post p = post(id, authorId, "被下架的帖子");
            p.setStatus(Post.STATUS_OFFLINE);
            return p;
        }

        @Test
        @DisplayName("三个字段都不给 → A0101，不静默返回成功")
        void rejectsEmptyAdminUpdate() {
            assertThatThrownBy(() -> postService.administrate(1L, 7L, null, null, null))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.PARAM_INVALID));

            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("★ 下架：状态真的变了，标签的帖子数要减 1")
        void takingOfflineDecrementsTagCount() {
            when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, "正常帖子"));

            postService.administrate(1L, 99L, null, null, Post.STATUS_OFFLINE);

            verify(postMapper).updateAttributes(1L, null, null, Post.STATUS_OFFLINE);
            // 不减的话，标签页写着"12 篇"、点进去只有 9 篇
            verify(tagService).adjustPostCountForTagsOf(1L, -1);
        }

        @Test
        @DisplayName("★ 重复下架：状态没变，**不能把标签计数减第二遍**")
        void repeatedOfflineDoesNotDecrementTwice() {
            when(postMapper.selectById(1L)).thenReturn(offlinePost(1L, 10L));

            postService.administrate(1L, 99L, null, null, Post.STATUS_OFFLINE);

            verify(postMapper).updateAttributes(1L, null, null, Post.STATUS_OFFLINE);
            verify(tagService, never()).adjustPostCountForTagsOf(anyLong(), anyInt());
        }

        @Test
        @DisplayName("恢复上架：标签计数加回来")
        void restoringIncrementsTagCount() {
            when(postMapper.selectById(1L)).thenReturn(offlinePost(1L, 10L));

            postService.administrate(1L, 99L, null, null, Post.STATUS_PUBLISHED);

            verify(tagService).adjustPostCountForTagsOf(1L, 1);
        }

        @Test
        @DisplayName("只改置顶 / 加精时，标签计数不受影响")
        void toppingDoesNotTouchTagCount() {
            when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, "正常帖子"));

            postService.administrate(1L, 99L, true, true, null);

            verify(postMapper).updateAttributes(1L, true, true, null);
            verify(tagService, never()).adjustPostCountForTagsOf(anyLong(), anyInt());
        }

        @Test
        @DisplayName("治理一条不存在的帖子 → A0201")
        void adminOnMissingPost() {
            when(postMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> postService.administrate(999L, 99L, true, null, null))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("正常的帖子谁都能看")
        void publishedPostIsVisibleToEveryone() {
            Post p = post(1L, 10L, "正常帖子");
            p.setStatus(Post.STATUS_PUBLISHED);
            p.setContent("正文");
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(anyLong())).thenReturn(Optional.empty());

            assertThat(postService.getDetail(1L, null, false).title()).isEqualTo("正常帖子");
            assertThat(postService.getDetail(1L, 999L, false).title()).isEqualTo("正常帖子");
        }

        @Test
        @DisplayName("★ 被下架的帖子：匿名访问当作不存在（404）")
        void offlinePostIsHiddenFromAnonymous() {
            when(postMapper.selectById(1L)).thenReturn(offlinePost(1L, 10L));

            assertThatThrownBy(() -> postService.getDetail(1L, null, false))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));

            // 当作不存在，而不是 403——403 等于告诉对方"这个 id 是存在的"
            verify(postViewCounter, never()).increment(anyLong(), any());
        }

        @Test
        @DisplayName("★ 被下架的帖子：别人也看不到")
        void offlinePostIsHiddenFromOthers() {
            when(postMapper.selectById(1L)).thenReturn(offlinePost(1L, 10L));

            assertThatThrownBy(() -> postService.getDetail(1L, 999L, false))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("★ 被下架的帖子：**作者本人仍然看得到**，且响应里明确标出「已被下架」")
        void offlinePostIsVisibleToItsAuthor() {
            Post p = offlinePost(1L, 10L);
            p.setContent("正文");
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(10L)).thenReturn(Optional.empty());

            PostDetail detail = postService.getDetail(1L, 10L, false);

            assertThat(detail.title()).isEqualTo("被下架的帖子");
            // 少了这个字段，"作者仍能打开"就只做了一半——他打开了，
            // 但看到的和正常帖子一模一样，仍然不知道发生了什么
            assertThat(detail.offline()).as("要告诉作者这篇被下架了").isTrue();
        }

        @Test
        @DisplayName("★ 被下架的帖子：版主也看得到（他要能复核自己的治理动作）")
        void offlinePostIsVisibleToModerator() {
            Post p = offlinePost(1L, 10L);
            p.setContent("正文");
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(10L)).thenReturn(Optional.empty());

            assertThat(postService.getDetail(1L, 999L, true).title()).isEqualTo("被下架的帖子");
        }

        @Test
        @DisplayName("正常的帖子，响应里 offline 为 false")
        void publishedPostIsNotMarkedOffline() {
            Post p = post(1L, 10L, "正常帖子");
            p.setContent("正文");
            when(postMapper.selectById(1L)).thenReturn(p);
            when(userQueryService.findBrief(anyLong())).thenReturn(Optional.empty());

            assertThat(postService.getDetail(1L, null, false).offline()).isFalse();
        }
    }

    @Nested
    @DisplayName("改帖与删帖")
    class UpdateAndDelete {

        @Test
        @DisplayName("只改标题时，摘要传 null —— 不重算（摘要由正文派生）")
        void shouldNotRecomputeSummaryWhenOnlyTitleChanges() {
            when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, "旧标题"));

            postService.update(1L, "新标题", null, null);

            verify(postMapper).updateContent(1L, "新标题", null, null);
        }

        @Test
        @DisplayName("改正文时摘要跟着一起更新")
        void shouldUpdateSummaryTogetherWithContent() {
            when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, "标题"));

            postService.update(1L, null, "新的正文\n带换行", null);

            ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
            verify(postMapper).updateContent(eq(1L), isNull(), eq("新的正文\n带换行"), summary.capture());
            // 不一起更新的话，列表页会一直显示旧摘要——很难一眼看出是哪里错了
            assertThat(summary.getValue()).isEqualTo("新的正文 带换行");
        }

        @Test
        @DisplayName("两个字段都不给 → A0101，而不是静默地什么都不做还返回成功")
        void shouldRejectEmptyUpdate() {
            assertThatThrownBy(() -> postService.update(1L, null, null, null))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.PARAM_INVALID));

            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("帖子不存在 → A0201（靠先查一次，不靠 UPDATE 的返回行数）")
        void shouldThrowWhenUpdatingMissingPost() {
            when(postMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> postService.update(999L, "t", "c", null))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("回归：改成和原值一样的内容，不该被误判成『帖子不存在』")
        void shouldNotMisreportMissingWhenNothingChanged() {
            // 用户打开帖子、什么都不改、直接点保存——这是最常见的操作之一。
            // 若用 UPDATE 的返回行数判断存在性，MySQL 返回的是 0（它数的是"改变了"的行数），
            // 于是用户会看到"帖子不存在"。这里断言它不抛异常。
            when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, "一模一样的标题"));

            postService.update(1L, "一模一样的标题", null, null);

            verify(postMapper).updateContent(1L, "一模一样的标题", null, null);
        }

        @Test
        @DisplayName("改帖带标签时，标签整体替换")
        void shouldReplaceTagsWhenProvided() {
            when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, "标题"));

            postService.update(1L, null, null, List.of("java", "mysql"));

            verify(tagService).replaceTags(1L, List.of("java", "mysql"));
            verify(postMapper, never()).updateContent(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("改帖不带标签时，标签不动（null 与空列表是两回事）")
        void shouldNotTouchTagsWhenTagsIsNull() {
            when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, "标题"));

            postService.update(1L, "新标题", null, null);

            verify(tagService, never()).replaceTags(anyLong(), any());
        }

        @Test
        @DisplayName("删帖走标记删除，并**先摘掉标签**（否则标签的使用次数永远减不回去）")
        void shouldDetachTagsThenSoftDelete() {
            when(postMapper.selectById(1L)).thenReturn(post(1L, 10L, "标题"));

            postService.delete(1L);

            // 顺序很重要：先 detachAll 再 softDelete
            var ordered = inOrder(tagService, postMapper);
            ordered.verify(tagService).detachAll(1L);
            ordered.verify(postMapper).softDelete(1L);
        }

        @Test
        @DisplayName("删一条不存在的帖子 → A0201，且**不动标签计数**")
        void shouldThrowWhenDeletingMissingPost() {
            when(postMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> postService.delete(999L))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));

            verify(tagService, never()).detachAll(anyLong());
            verify(postMapper, never()).softDelete(anyLong());
        }
    }
}
