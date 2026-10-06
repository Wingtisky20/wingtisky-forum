package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.content.cache.PostDetailCache;
import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.forum.content.mapper.PostCollectMapper;
import com.wingtisky.forum.forum.content.mapper.PostLikeMapper;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InteractionService} 的单元测试。
 *
 * <p>核心只有一条：**是否真的写进去了，决定要不要动计数**。
 * 少了这个判断，同一个人点十次赞，帖子的点赞数就是十——而且不会报错，
 * 只是在界面上显示一个没人看得出错在哪的数字。
 *
 * <p>辅助线是"对一条不存在的帖子点赞"要报错，而不是安静地插一行孤儿关联。
 */
@ExtendWith(MockitoExtension.class)
class InteractionServiceTest {

    private static final Long POST_ID = 1L;
    private static final Long ME = 7L;

    @Mock
    private PostMapper postMapper;

    @Mock
    private PostLikeMapper postLikeMapper;

    @Mock
    private PostCollectMapper postCollectMapper;

    /** 帖子的详情缓存。点赞会改 `likeCount`，而它就在缓存对象里。 */
    @Mock
    private PostDetailCache postDetailCache;

    private InteractionService interactionService;

    @BeforeEach
    void setUp() {
        interactionService = new InteractionService(postMapper, postLikeMapper, postCollectMapper,
                postDetailCache);
    }

    private static Post existingPost() {
        Post p = new Post();
        p.setId(POST_ID);
        return p;
    }

    private static BizException err(Throwable e) {
        return (BizException) e;
    }

    @Nested
    @DisplayName("点赞")
    class Like {

        @Test
        @DisplayName("第一次点赞：插进去了，计数 +1")
        void firstLikeBumpsCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(existingPost());
            when(postLikeMapper.insertIgnore(ME, POST_ID)).thenReturn(1);

            interactionService.like(POST_ID, ME);

            verify(postMapper).addLikeCount(POST_ID, 1);
            // M3 Task 7：点赞改了 likeCount，而它在缓存对象里 → 这一条帖子必须失效
            verify(postDetailCache).evictAfterCommit(POST_ID);
        }

        @Test
        @DisplayName("★ 重复点赞：插入被忽略，**计数一点都不动**，缓存也不删")
        void repeatedLikeDoesNotBumpCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(existingPost());
            // INSERT IGNORE 撞主键 → 返回 0
            when(postLikeMapper.insertIgnore(ME, POST_ID)).thenReturn(0);

            interactionService.like(POST_ID, ME);

            // 这一条是整个 Task 里最要紧的断言：没有它，同一个人点十次赞，
            // 帖子的点赞数就是十，而且看起来完全正常
            verify(postMapper, never()).addLikeCount(anyLong(), anyInt());
            // 什么都没改，就不该删缓存——连点 3 次赞只删 1 次。
            // 删了也不出错，只是让下一次读白回源一次；钉住它是为了让"哪些写要失效"
            // 这条规则始终是**由"是否真的改了"决定的**，而不是"调了这个方法就删"
            verify(postDetailCache, never()).evictAfterCommit(anyLong());
        }

        @Test
        @DisplayName("对不存在的帖子点赞 → A0201，且不写关联")
        void likeOnMissingPostFails() {
            when(postMapper.selectById(POST_ID)).thenReturn(null);

            assertThatThrownBy(() -> interactionService.like(POST_ID, ME))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(err(e).getErrorCode())
                            .isEqualTo(ErrorCode.POST_NOT_FOUND));

            verifyNoInteractions(postLikeMapper);
        }
    }

    @Nested
    @DisplayName("取消点赞")
    class Unlike {

        @Test
        @DisplayName("确实点过 → 删掉一行，计数 -1")
        void unlikeBumpsCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(existingPost());
            when(postLikeMapper.delete(ME, POST_ID)).thenReturn(1);

            interactionService.unlike(POST_ID, ME);

            verify(postMapper).addLikeCount(POST_ID, -1);
            verify(postDetailCache).evictAfterCommit(POST_ID);
        }

        @Test
        @DisplayName("★ 本来就没点过 → 删 0 行，**计数一点都不动**（否则会减掉别人的赞），缓存也不删")
        void unlikeWhenNeverLikedDoesNotBumpCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(existingPost());
            when(postLikeMapper.delete(ME, POST_ID)).thenReturn(0);

            interactionService.unlike(POST_ID, ME);

            verify(postMapper, never()).addLikeCount(anyLong(), anyInt());
            verify(postDetailCache, never()).evictAfterCommit(anyLong());
        }
    }

    @Nested
    @DisplayName("收藏与取消收藏")
    class Collect {

        @Test
        @DisplayName("第一次收藏：计数 +1")
        void firstCollectBumpsCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(existingPost());
            when(postCollectMapper.insertIgnore(ME, POST_ID)).thenReturn(1);

            interactionService.collect(POST_ID, ME);

            verify(postMapper).addCollectCount(POST_ID, 1);
            verify(postDetailCache).evictAfterCommit(POST_ID);
        }

        @Test
        @DisplayName("重复收藏：计数不动，缓存也不删")
        void repeatedCollectDoesNotBumpCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(existingPost());
            when(postCollectMapper.insertIgnore(ME, POST_ID)).thenReturn(0);

            interactionService.collect(POST_ID, ME);

            verify(postMapper, never()).addCollectCount(anyLong(), anyInt());
            verify(postDetailCache, never()).evictAfterCommit(anyLong());
        }

        @Test
        @DisplayName("取消收藏：删掉一行才减计数，并删缓存")
        void uncollectBumpsCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(existingPost());
            when(postCollectMapper.delete(ME, POST_ID)).thenReturn(1);

            interactionService.uncollect(POST_ID, ME);

            verify(postMapper).addCollectCount(POST_ID, -1);
            verify(postDetailCache).evictAfterCommit(POST_ID);
        }

        @Test
        @DisplayName("没收藏过就取消：计数不动，缓存也不删")
        void uncollectWhenNeverCollectedDoesNotBumpCount() {
            when(postMapper.selectById(POST_ID)).thenReturn(existingPost());
            when(postCollectMapper.delete(ME, POST_ID)).thenReturn(0);

            interactionService.uncollect(POST_ID, ME);

            verify(postMapper, never()).addCollectCount(anyLong(), anyInt());
            verify(postDetailCache, never()).evictAfterCommit(anyLong());
        }
    }

    @Nested
    @DisplayName("查询『我点过没』")
    class Query {

        @Test
        @DisplayName("点过 → true；没点过 → false")
        void reportsLikedState() {
            when(postLikeMapper.exists(ME, POST_ID)).thenReturn(1);
            assertThat(interactionService.liked(POST_ID, ME)).isTrue();

            when(postLikeMapper.exists(ME, POST_ID)).thenReturn(0);
            assertThat(interactionService.liked(POST_ID, ME)).isFalse();
        }

        @Test
        @DisplayName("★ 未登录（userId 为 null）→ false，且**不查库**")
        void anonymousDoesNotQuery() {
            assertThat(interactionService.liked(POST_ID, null)).isFalse();
            assertThat(interactionService.collected(POST_ID, null)).isFalse();

            verifyNoInteractions(postLikeMapper, postCollectMapper);
        }

        @Test
        @DisplayName("收藏状态同样")
        void reportsCollectedState() {
            when(postCollectMapper.exists(ME, POST_ID)).thenReturn(1);

            assertThat(interactionService.collected(POST_ID, ME)).isTrue();
        }
    }
}
