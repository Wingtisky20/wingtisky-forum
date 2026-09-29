package com.wingtisky.forum.forum.user.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.user.entity.Role;
import com.wingtisky.forum.forum.user.entity.RoleCode;
import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.mapper.RoleMapper;
import com.wingtisky.forum.forum.user.mapper.UserMapper;
import com.wingtisky.forum.forum.user.mapper.UserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserService} 的单元测试。
 *
 * <p>用 Mockito 替换 Mapper，**不连数据库**——这里要验的是"领域规则"，
 * 不是"SQL 写得对不对"。后者由集成测试与真实数据库负责。
 * 把两者分开的好处是这一层能在 CI 上跑（ADR-0007：集成测试只在本地跑）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private UserRoleMapper userRoleMapper;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, roleMapper, userRoleMapper, passwordEncoder);
    }

    @Nested
    @DisplayName("注册")
    class Register {

        @Test
        @DisplayName("密码以 BCrypt 哈希入库，绝不存明文")
        void storesHashedPasswordNeverPlaintext() {
            when(roleMapper.selectByCode(RoleCode.USER.name())).thenReturn(roleWithId(1L));

            userService.register("alice", "raw-password-123", "爱丽丝");

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(captor.capture());
            String stored = captor.getValue().getPassword();

            assertThat(stored).isNotEqualTo("raw-password-123");
            assertThat(stored).startsWith("$2a$");                  // BCrypt 的格式标识
            assertThat(passwordEncoder.matches("raw-password-123", stored)).isTrue();
        }

        @Test
        @DisplayName("用户名重复由唯一索引兜底：捕获 DuplicateKeyException 转成业务错误码")
        void duplicateUsernameIsTranslatedToBusinessError() {
            when(userMapper.insert(any(User.class)))
                    .thenThrow(new DuplicateKeyException("uk_username"));

            assertThatThrownBy(() -> userService.register("bob", "pw", "鲍勃"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USERNAME_EXISTS);

            // 关键：插入失败时**不能**继续建角色关联，否则会留下角色表里的孤儿记录
            verify(userRoleMapper, never()).insert(anyLong(), anyLong());
        }

        @Test
        @DisplayName("注册成功后自动分配默认角色 USER")
        void assignsDefaultRoleOnSuccess() {
            Role userRole = roleWithId(7L);
            when(roleMapper.selectByCode(RoleCode.USER.name())).thenReturn(userRole);
            // 模拟真实 MyBatis 的 useGeneratedKeys：插入时把自增主键回填到入参对象上。
            // 不模拟的话 user.getId() 是 null，而注册流程紧接着要用它建角色关联——
            // 真实环境里漏配 useGeneratedKeys 的表现正是"角色关联插入了 null"。
            when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, User.class).setId(1L);
                return 1;
            });

            userService.register("carol", "pw", "卡罗尔");

            verify(userRoleMapper).insert(eq(1L), eq(7L));
        }

        @Test
        @DisplayName("默认角色没初始化时，报系统错误而不是业务错误（这是部署问题）")
        void missingDefaultRoleIsASystemError() {
            when(roleMapper.selectByCode(RoleCode.USER.name())).thenReturn(null);

            assertThatThrownBy(() -> userService.register("dave", "pw", "戴夫"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SYSTEM_ERROR);
        }

        private Role roleWithId(Long id) {
            Role role = new Role();
            role.setId(id);
            role.setCode(RoleCode.USER.name());
            return role;
        }
    }

    @Nested
    @DisplayName("登录校验")
    class Authenticate {

        @Test
        @DisplayName("用户不存在与密码错误返回同一个错误码——否则接口就是用户名探测器")
        void unknownUserAndWrongPasswordAreIndistinguishable() {
            ErrorCode whenUserMissing = errorCodeOf(() -> userService.authenticate("nobody", "pw"));

            when(userMapper.selectByUsername("alice")).thenReturn(activeUser("alice", "correct-pw"));
            ErrorCode whenPasswordWrong = errorCodeOf(() -> userService.authenticate("alice", "wrong-pw"));

            assertThat(whenUserMissing).isEqualTo(ErrorCode.LOGIN_FAILED);
            assertThat(whenPasswordWrong).isEqualTo(ErrorCode.LOGIN_FAILED);
        }

        @Test
        @DisplayName("账号被禁用要单独报——但只在密码正确之后，否则会泄露用户名是否存在")
        void disabledAccountIsReportedOnlyAfterPasswordCheck() {
            User disabled = activeUser("eve", "correct-pw");
            disabled.setStatus(User.STATUS_DISABLED);
            when(userMapper.selectByUsername("eve")).thenReturn(disabled);

            // 密码对 → 才告诉他账号被禁用（他自己知道密码，不算泄露）
            assertThat(errorCodeOf(() -> userService.authenticate("eve", "correct-pw")))
                    .isEqualTo(ErrorCode.ACCOUNT_DISABLED);

            // 密码错 → 仍然是笼统的"用户名或密码错误"，不暴露"这个账号存在但被禁用了"
            assertThat(errorCodeOf(() -> userService.authenticate("eve", "wrong-pw")))
                    .isEqualTo(ErrorCode.LOGIN_FAILED);
        }

        @Test
        @DisplayName("正确凭据返回用户")
        void returnsUserOnValidCredentials() {
            when(userMapper.selectByUsername("frank")).thenReturn(activeUser("frank", "correct-pw"));

            User user = userService.authenticate("frank", "correct-pw");

            assertThat(user.getUsername()).isEqualTo("frank");
        }

        private User activeUser(String username, String rawPassword) {
            User user = new User();
            user.setId(1L);
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(rawPassword));
            user.setStatus(User.STATUS_ACTIVE);
            return user;
        }
    }

    @Nested
    @DisplayName("改资料")
    class UpdateProfile {

        @Test
        @DisplayName("三个字段全为空时报参数错误，不让它生成空的 SET 子句")
        void rejectsUpdateWithNoFields() {
            assertThatThrownBy(() -> userService.updateProfile(1L, null, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PARAM_INVALID);

            verify(userMapper, never()).updateProfile(any(User.class));
        }

        @Test
        @DisplayName("只改昵称时，其余字段保持 null（交给动态 SQL 跳过）")
        void updatesOnlyProvidedFields() {
            userService.updateProfile(1L, "新昵称", null, null);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userMapper).updateProfile(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(1L);
            assertThat(captor.getValue().getNickname()).isEqualTo("新昵称");
            assertThat(captor.getValue().getAvatar()).isNull();
            assertThat(captor.getValue().getEmail()).isNull();
        }
    }

    @Nested
    @DisplayName("查询")
    class Query {

        @Test
        @DisplayName("用户不存在时抛业务错误，而不是返回 null 让调用方空指针")
        void missingUserThrowsInsteadOfReturningNull() {
            when(userMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> userService.getById(99L))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    private ErrorCode errorCodeOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("期望抛出 BizException，但没有抛出");
        } catch (BizException e) {
            return e.getErrorCode();
        }
    }
}
