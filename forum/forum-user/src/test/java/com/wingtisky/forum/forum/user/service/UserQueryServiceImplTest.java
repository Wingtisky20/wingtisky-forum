package com.wingtisky.forum.forum.user.service;

import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserQueryServiceImpl} 的单元测试。
 *
 * <p>用 Mockito 替换 Mapper，**不连数据库**——这里要验的是三件事，都不是 SQL 本身：
 * <ol>
 *   <li><b>投影转换对不对</b>（字段有没有串位）</li>
 *   <li><b>查不到的 id 有没有被补位</b>（契约要求不补）</li>
 *   <li><b>空输入有没有短路</b>——这条最重要：短路漏了的话，会拼出 {@code IN ()}
 *       这种非法 SQL，而它在"这一页一个作者都没有"时才会炸，是本类最容易漏测的地方</li>
 * </ol>
 * SQL 本身对不对由集成测试与真实数据库负责（ADR-0007：集成测试只在本地跑，
 * 所以这一层刻意不碰数据库，才能在 CI 上跑）。
 */
@ExtendWith(MockitoExtension.class)
class UserQueryServiceImplTest {

    @Mock
    private UserMapper userMapper;

    private UserQueryServiceImpl userQueryService;

    @BeforeEach
    void setUp() {
        userQueryService = new UserQueryServiceImpl(userMapper);
    }

    /** 造一个"只填了三个字段"的 User——就是 mapper 那条查询返回的形状。 */
    private static User briefUser(long id, String nickname, String avatar) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        user.setAvatar(avatar);
        return user;
    }

    @Nested
    @DisplayName("批量查询 findBriefs")
    class FindBriefs {

        @Test
        @DisplayName("按 id 建索引：调用方拿 authorId 就能直接取到作者")
        void shouldIndexByUserId() {
            when(userMapper.selectBriefsByIds(List.of(1L, 2L))).thenReturn(List.of(
                    briefUser(1L, "深栈用户", null),
                    briefUser(2L, "另一个人", "http://localhost/avatar/2.png")));

            Map<Long, UserBrief> result = userQueryService.findBriefs(List.of(1L, 2L));

            assertThat(result).hasSize(2);
            assertThat(result.get(1L).nickname()).isEqualTo("深栈用户");
            assertThat(result.get(2L).avatar()).isEqualTo("http://localhost/avatar/2.png");
        }

        @Test
        @DisplayName("查不到的 id 不补位——调用方要能区分『用户被删』与『没这回id』")
        void shouldNotBackfillMissingIds() {
            // 请求两个 id，库里只查得到一个（另一个被逻辑删除了）
            when(userMapper.selectBriefsByIds(any())).thenReturn(List.of(briefUser(1L, "还在的", null)));

            Map<Long, UserBrief> result = userQueryService.findBriefs(List.of(1L, 999L));

            assertThat(result).containsOnlyKeys(1L);
            assertThat(result).doesNotContainKey(999L);
        }

        @Test
        @DisplayName("空集合直接返回空 Map，**且根本不查库**（短路）")
        void shouldShortCircuitOnEmptyInput() {
            Map<Long, UserBrief> result = userQueryService.findBriefs(List.of());

            assertThat(result).isEmpty();
            // 这一条才是关键：只断言"返回空"证明不了短路——
            // 就算查了库、拼出非法的 IN ()，只要没真连库也可能"返回空"
            verify(userMapper, never()).selectBriefsByIds(any());
        }

        @Test
        @DisplayName("null 集合同样短路，不抛 NPE")
        void shouldShortCircuitOnNullInput() {
            assertThat(userQueryService.findBriefs(null)).isEmpty();
            verify(userMapper, never()).selectBriefsByIds(any());
        }
    }

    @Nested
    @DisplayName("单个查询 findBrief")
    class FindBrief {

        @Test
        @DisplayName("查得到时三个字段一一对应，不串位")
        void shouldMapFieldsWithoutSwapping() {
            when(userMapper.selectBriefsByIds(List.of(7L)))
                    .thenReturn(List.of(briefUser(7L, "昵称在这", "头像在这")));

            Optional<UserBrief> result = userQueryService.findBrief(7L);

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo(7L);
            assertThat(result.get().nickname()).isEqualTo("昵称在这");
            assertThat(result.get().avatar()).isEqualTo("头像在这");
        }

        @Test
        @DisplayName("查不到返回 empty——用户注销在展示场景里是正常情况，不是错误")
        void shouldReturnEmptyWhenMissing() {
            when(userMapper.selectBriefsByIds(List.of(7L))).thenReturn(List.of());

            assertThat(userQueryService.findBrief(7L)).isEmpty();
        }

        @Test
        @DisplayName("传 null 直接返回 empty，不查库")
        void shouldShortCircuitOnNullId() {
            assertThat(userQueryService.findBrief(null)).isEmpty();
            verify(userMapper, never()).selectBriefsByIds(any());
        }
    }
}
