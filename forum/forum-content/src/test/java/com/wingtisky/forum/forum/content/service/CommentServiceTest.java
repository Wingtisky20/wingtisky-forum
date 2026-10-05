package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.domain.user.UserQueryService;
import com.wingtisky.forum.forum.content.dto.CommentView;
import com.wingtisky.forum.forum.content.dto.PageResult;
import com.wingtisky.forum.forum.content.entity.Comment;
import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.forum.content.mapper.CommentMapper;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CommentService} 的单元测试。
 *
 * <p>这个类里有一个**很容易写错、写错又不会报错**的地方：回复一条回复时
 * {@code rootId} 该取什么。取错的话那条回复会成为一条"谁也看不见"的孤儿
 * （列表只查 {@code root_id = 0}）——数据在库里，页面上没有。
 * 所以本测试对那一行盯得最紧。
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    private static final Long POST_ID = 1L;
    private static final Long ME = 7L;

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private PostMapper postMapper;

    @Mock
    private UserQueryService userQueryService;

    private CommentService commentService;

    @BeforeEach
    void setUp() {
        commentService = new CommentService(commentMapper, postMapper, userQueryService);
    }

    private static Post post(long id) {
        Post p = new Post();
        p.setId(id);
        return p;
    }

    private static Comment comment(long id, long postId, long rootId, Long parentId) {
        Comment c = new Comment();
        c.setId(id);
        c.setPostId(postId);
        c.setAuthorId(ME);
        c.setRootId(rootId);
        c.setParentId(parentId);
        c.setContent("内容");
        c.setCreateTime(LocalDateTime.of(2026, 10, 5, 12, 0));
        return c;
    }

    /** 取出发往 Mapper 的那条评论。 */
    private Comment capturedInsert() {
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static BizException err(Throwable e) {
        return (BizException) e;
    }

    @Nested
    @DisplayName("发评论与回复")
    class Create {

        @Test
        @DisplayName("顶层评论：parentId 与 rootId 都是 0")
        void topLevelCommentHasNoParents() {
            when(postMapper.selectById(POST_ID)).thenReturn(post(POST_ID));

            commentService.create(POST_ID, ME, null, "第一条评论");

            Comment inserted = capturedInsert();
            assertThat(inserted.getParentId()).isZero();
            assertThat(inserted.getRootId()).isZero();
            assertThat(inserted.isTopLevel()).isTrue();
        }

        @Test
        @DisplayName("回复一条顶层评论：rootId 取被回复评论的 id")
        void replyToTopLevelPointsRootAtIt() {
            when(postMapper.selectById(POST_ID)).thenReturn(post(POST_ID));
            when(commentMapper.selectById(100L)).thenReturn(comment(100L, POST_ID, 0L, 0L));

            commentService.create(POST_ID, ME, 100L, "回复第一楼");

            Comment inserted = capturedInsert();
            assertThat(inserted.getParentId()).isEqualTo(100L);
            assertThat(inserted.getRootId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("★ 回复一条回复：rootId 取**父评论的 rootId**，不是父评论的 id")
        void replyToReplyKeepsTheSameRoot() {
            // 父评论是一条回复：它自己是 200，属于 100 那一楼
            when(postMapper.selectById(POST_ID)).thenReturn(post(POST_ID));
            when(commentMapper.selectById(200L)).thenReturn(comment(200L, POST_ID, 100L, 100L));

            commentService.create(POST_ID, ME, 200L, "回复那条回复");

            Comment inserted = capturedInsert();
            assertThat(inserted.getParentId()).as("直接回复的是 200").isEqualTo(200L);
            // 若这里写成 parentComment.getId()(=200)，这条评论会变成一条
            // root_id=200 的"孤儿"：它既不在 100 楼里，也不会出现在顶层列表里
            assertThat(inserted.getRootId())
                    .as("但它仍然属于 100 那一楼")
                    .isEqualTo(100L);
        }

        @Test
        @DisplayName("不许跨帖回复 → A0101")
        void shouldRejectCrossPostReply() {
            when(postMapper.selectById(POST_ID)).thenReturn(post(POST_ID));
            // 这条评论属于**另一条帖子**（postId = 999）
            when(commentMapper.selectById(100L)).thenReturn(comment(100L, 999L, 0L, 0L));

            assertThatThrownBy(() -> commentService.create(POST_ID, ME, 100L, "内容"))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(err(e).getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

            verify(commentMapper, never()).insert(any());
        }

        @Test
        @DisplayName("回复一条不存在的评论 → A0202")
        void shouldRejectReplyToMissingComment() {
            when(postMapper.selectById(POST_ID)).thenReturn(post(POST_ID));
            when(commentMapper.selectById(100L)).thenReturn(null);

            assertThatThrownBy(() -> commentService.create(POST_ID, ME, 100L, "内容"))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(err(e).getErrorCode()).isEqualTo(ErrorCode.COMMENT_NOT_FOUND));
        }

        @Test
        @DisplayName("在一条不存在的帖子下评论 → A0201")
        void shouldRejectCommentOnMissingPost() {
            when(postMapper.selectById(POST_ID)).thenReturn(null);

            assertThatThrownBy(() -> commentService.create(POST_ID, ME, null, "内容"))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(err(e).getErrorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("发一条评论，帖子的评论数 +1")
        void shouldBumpCommentCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(post(POST_ID));

            commentService.create(POST_ID, ME, null, "内容");

            verify(postMapper).addCommentCount(POST_ID, 1);
        }
    }

    @Nested
    @DisplayName("评论列表")
    class Page {

        @Test
        @DisplayName("这一页所有楼的回复**一次**取回，不是每楼查一次")
        void shouldLoadRepliesInOneBatch() {
            when(commentMapper.countTopLevel(POST_ID)).thenReturn(2L);
            when(commentMapper.selectTopLevelPage(POST_ID, 0, 20)).thenReturn(List.of(
                    comment(100L, POST_ID, 0L, 0L),
                    comment(101L, POST_ID, 0L, 0L)));
            when(commentMapper.selectRepliesByRootIds(Set.of(100L, 101L))).thenReturn(List.of(
                    comment(200L, POST_ID, 100L, 100L)));
            when(userQueryService.findBriefs(any())).thenReturn(Map.of(
                    ME, new UserBrief(ME, "我", null)));

            PageResult<CommentView> result = commentService.page(POST_ID, 1, 20);

            assertThat(result.total()).isEqualTo(2);
            assertThat(result.items()).hasSize(2);
            assertThat(result.items().get(0).replies()).hasSize(1);
            assertThat(result.items().get(1).replies()).isEmpty();

            // 关键断言：回复**只查了一次**，参数是这一页所有楼的 id
            verify(commentMapper, times(1)).selectRepliesByRootIds(Set.of(100L, 101L));
            // 作者也只查了一次（顶层评论与回复的作者合在一起）
            verify(userQueryService, times(1)).findBriefs(any());
        }

        @Test
        @DisplayName("一条评论都没有时，连列表查询都不发")
        void shouldShortCircuitWhenNoComments() {
            when(commentMapper.countTopLevel(POST_ID)).thenReturn(0L);

            PageResult<CommentView> result = commentService.page(POST_ID, 1, 20);

            assertThat(result.items()).isEmpty();
            verify(commentMapper, never()).selectTopLevelPage(anyLong(), anyInt(), anyInt());
            verifyNoInteractions(userQueryService);
        }

        @Test
        @DisplayName("页码超出范围 → 返回空页，且**不去查回复**（否则会拼出非法的 IN ()）")
        void shouldShortCircuitWhenPageBeyondRange() {
            when(commentMapper.countTopLevel(POST_ID)).thenReturn(5L);
            // 总共 5 条却要第 100 页 → 这一页一条也没有
            when(commentMapper.selectTopLevelPage(POST_ID, (100 - 1) * 20, 20)).thenReturn(List.of());

            PageResult<CommentView> result = commentService.page(POST_ID, 100, 20);

            assertThat(result.items()).isEmpty();
            assertThat(result.total()).as("总数仍然是 5，前端据此画出正确的页码").isEqualTo(5L);
            verify(commentMapper, never()).selectRepliesByRootIds(any());
            verifyNoInteractions(userQueryService);
        }
    }

    @Nested
    @DisplayName("删评论")
    class Delete {

        @Test
        @DisplayName("删顶层评论 → 连带删掉它的回复，评论数减去「1 + 回复数」")
        void deletingTopLevelCascadesToReplies() {
            when(commentMapper.selectById(100L)).thenReturn(comment(100L, POST_ID, 0L, 0L));
            when(commentMapper.softDelete(100L)).thenReturn(1);
            when(commentMapper.softDeleteReplies(100L)).thenReturn(3);

            commentService.delete(100L);

            verify(commentMapper).softDeleteReplies(100L);
            verify(postMapper).addCommentCount(POST_ID, -4);
        }

        @Test
        @DisplayName("删一条回复 → 只删它自己，不连带，评论数 -1")
        void deletingReplyDoesNotCascade() {
            when(commentMapper.selectById(200L)).thenReturn(comment(200L, POST_ID, 100L, 100L));
            when(commentMapper.softDelete(200L)).thenReturn(1);

            commentService.delete(200L);

            verify(commentMapper, never()).softDeleteReplies(anyLong());
            verify(postMapper).addCommentCount(POST_ID, -1);
        }

        @Test
        @DisplayName("删一条不存在的评论 → A0202，且不动评论数")
        void shouldThrowWhenDeletingMissingComment() {
            when(commentMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> commentService.delete(999L))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(err(e).getErrorCode()).isEqualTo(ErrorCode.COMMENT_NOT_FOUND));

            verify(postMapper, never()).addCommentCount(anyLong(), anyInt());
        }
    }
}
