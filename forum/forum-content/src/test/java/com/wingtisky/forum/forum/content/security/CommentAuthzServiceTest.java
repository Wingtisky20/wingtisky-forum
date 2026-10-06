package com.wingtisky.forum.forum.content.security;

import com.wingtisky.forum.forum.content.entity.Comment;
import com.wingtisky.forum.forum.content.mapper.CommentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CommentAuthzService} 的单元测试。
 *
 * <p>它比帖子那边的同名类更容易出错——因为现在项目里有**三个**长得一样的
 * 归属校验 Bean（管用户、管帖子、管评论），参数传串了不报错，只判错。
 */
@ExtendWith(MockitoExtension.class)
class CommentAuthzServiceTest {

    private static final Long LOGGED_IN_USER = 7L;

    @Mock
    private CommentMapper commentMapper;

    private CommentAuthzService commentAuthzService;

    @BeforeEach
    void setUp() {
        commentAuthzService = new CommentAuthzService(commentMapper);
        loginAs(LOGGED_IN_USER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static Comment commentBy(long authorId) {
        Comment c = new Comment();
        c.setId(1L);
        c.setAuthorId(authorId);
        return c;
    }

    @Test
    @DisplayName("评论是当前登录用户发的 → 是所有者")
    void shouldReturnTrueForOwnComment() {
        when(commentMapper.selectById(1L)).thenReturn(commentBy(LOGGED_IN_USER));

        assertThat(commentAuthzService.isOwner(1L)).isTrue();
    }

    @Test
    @DisplayName("评论是别人发的 → 不是所有者")
    void shouldReturnFalseForSomeoneElsesComment() {
        when(commentMapper.selectById(1L)).thenReturn(commentBy(999L));

        assertThat(commentAuthzService.isOwner(1L)).isFalse();
    }

    @Test
    @DisplayName("评论不存在（或已删）→ 不是所有者，且不抛异常")
    void shouldReturnFalseWhenCommentMissing() {
        when(commentMapper.selectById(1L)).thenReturn(null);

        assertThat(commentAuthzService.isOwner(1L)).isFalse();
    }

    @Test
    @DisplayName("未登录 → 不是所有者，且不查库")
    void shouldReturnFalseWhenNotLoggedIn() {
        SecurityContextHolder.clearContext();

        assertThat(commentAuthzService.isOwner(1L)).isFalse();
        verify(commentMapper, never()).selectById(1L);
    }

    @Test
    @DisplayName("评论 ID 为 null → 不是所有者，且不查库")
    void shouldReturnFalseForNullCommentId() {
        assertThat(commentAuthzService.isOwner(null)).isFalse();
        verify(commentMapper, never()).selectById(null);
    }
}
