package com.wingtisky.forum.forum.content.security;

import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PostAuthzService} 的单元测试。
 *
 * <p>这个类只有一行逻辑，但它守的是**越权**——写错的表现是"谁都改不了"或者
 * 更糟的"某些人什么都能改"。所以它值得一组专门的测试。
 *
 * <p>测试要手动往 {@code SecurityContextHolder} 里放认证信息——
 * 这正是过滤器在真实请求里做的事（把 userId 作为 principal）。
 */
@ExtendWith(MockitoExtension.class)
class PostAuthzServiceTest {

    private static final Long LOGGED_IN_USER = 7L;

    @Mock
    private PostMapper postMapper;

    private PostAuthzService postAuthzService;

    @BeforeEach
    void setUp() {
        postAuthzService = new PostAuthzService(postMapper);
        loginAs(LOGGED_IN_USER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of()));
    }

    private static void logout() {
        SecurityContextHolder.clearContext();
    }

    private static Post postBy(long authorId) {
        Post post = new Post();
        post.setId(1L);
        post.setAuthorId(authorId);
        return post;
    }

    @Test
    @DisplayName("帖子是当前登录用户发的 → 是所有者")
    void shouldReturnTrueForOwnPost() {
        when(postMapper.selectById(1L)).thenReturn(postBy(LOGGED_IN_USER));

        assertThat(postAuthzService.isOwner(1L)).isTrue();
    }

    @Test
    @DisplayName("帖子是别人发的 → 不是所有者")
    void shouldReturnFalseForSomeoneElsesPost() {
        when(postMapper.selectById(1L)).thenReturn(postBy(999L));

        assertThat(postAuthzService.isOwner(1L)).isFalse();
    }

    @Test
    @DisplayName("帖子不存在（或已删）→ 不是所有者，且不抛异常")
    void shouldReturnFalseWhenPostMissing() {
        when(postMapper.selectById(1L)).thenReturn(null);

        assertThat(postAuthzService.isOwner(1L)).isFalse();
    }

    @Test
    @DisplayName("未登录 → 不是所有者，且不查库")
    void shouldReturnFalseWhenNotLoggedIn() {
        logout();

        assertThat(postAuthzService.isOwner(1L)).isFalse();
        verify(postMapper, never()).selectById(1L);
    }

    @Test
    @DisplayName("帖子 ID 为 null → 不是所有者，且不查库")
    void shouldReturnFalseForNullPostId() {
        assertThat(postAuthzService.isOwner(null)).isFalse();
        verify(postMapper, never()).selectById(null);
    }

    @Test
    @DisplayName("回归：作者的用户 ID 与帖子 ID 相同，也能正确判断出『这不是我的帖』")
    void shouldNotConfusePostIdWithUserId() {
        // 这正是复用用户域 @authz.isOwner(#id) 会踩的坑：
        // 那种写法比的是"帖子 ID 是否等于我的用户 ID"——本例里两者都是 7，
        // 于是一个**别人的**帖子会被误判成"我的"。
        when(postMapper.selectById(7L)).thenReturn(postBy(999L));
        loginAs(7L);

        assertThat(postAuthzService.isOwner(7L))
                .as("必须比对帖子的 authorId，而不是把帖子 ID 当用户 ID 用")
                .isFalse();
    }
}
