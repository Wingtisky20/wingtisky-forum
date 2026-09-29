package com.wingtisky.forum;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.mapper.UserMapper;
import com.wingtisky.forum.forum.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册流程的集成测试：**连真实的 MySQL**（test 库），验证单元测试覆盖不到的部分。
 *
 * <p><b>为什么放在 app 模块</b>：集成测试需要完整的 Spring 上下文，而唯一的
 * {@code @SpringBootApplication} 在 app；且 app 是唯一同时看得见所有业务模块的装配模块。
 *
 * <p><b>为什么这些必须连真库才能验</b>：
 * <ul>
 *   <li>Mapper XML 里的 SQL 写错——单元测试用 Mockito 替换了 Mapper，**一行 SQL 都不执行**</li>
 *   <li>{@code useGeneratedKeys} 有没有配——漏了的话 {@code user.getId()} 为 null，
 *       表现为"角色关联插入了 null"</li>
 *   <li>唯一索引真的存在吗——"用户名重复"这个约束在数据库上，不在代码里</li>
 * </ul>
 *
 * <p><b>@Transactional 的作用是让每个测试最后回滚</b>，test 库跑完保持干净。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class UserRegistrationIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("注册 → 落库 → 能按用户名查回来，密码是哈希且可校验")
    void registerPersistsUserWithHashedPassword() {
        User registered = userService.register("it_alice", "raw-pw-12345", "爱丽丝");

        // useGeneratedKeys 是否生效：这个 id 由数据库生成并回填
        assertThat(registered.getId()).isNotNull();

        User loaded = userMapper.selectByUsername("it_alice");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(registered.getId());
        assertThat(loaded.getNickname()).isEqualTo("爱丽丝");
        assertThat(loaded.getStatus()).isEqualTo(User.STATUS_ACTIVE);

        // 库里存的是哈希，不是明文；且能校验通过
        assertThat(loaded.getPassword()).isNotEqualTo("raw-pw-12345");
        assertThat(passwordEncoder.matches("raw-pw-12345", loaded.getPassword())).isTrue();
    }

    @Test
    @DisplayName("注册时自动分配 USER 角色，且角色码能查回来")
    void registerAssignsDefaultRole() {
        User registered = userService.register("it_bob", "raw-pw-12345", "鲍勃");

        List<String> roles = userService.getRoleCodes(registered.getId());

        assertThat(roles).containsExactly("USER");
    }

    @Test
    @DisplayName("同一个用户名注册两次：由数据库唯一索引拦下，转成 USERNAME_EXISTS")
    void duplicateUsernameIsRejectedByTheUniqueIndex() {
        userService.register("it_carol", "raw-pw-12345", "卡罗尔");

        // 这条验证的是**数据库上的唯一索引真的存在**——如果把建表脚本里的
        // uk_username 去掉，单元测试仍然全绿，只有这条会失败。
        assertThatThrownBy(() -> userService.register("it_carol", "another-pw", "另一个卡罗尔"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USERNAME_EXISTS);
    }

    @Test
    @DisplayName("登录校验走真实查询：正确凭据通过，错误凭据失败")
    void authenticateAgainstRealDatabase() {
        userService.register("it_dave", "raw-pw-12345", "戴夫");

        User ok = userService.authenticate("it_dave", "raw-pw-12345");
        assertThat(ok.getUsername()).isEqualTo("it_dave");

        assertThatThrownBy(() -> userService.authenticate("it_dave", "wrong-pw"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);

        assertThatThrownBy(() -> userService.authenticate("no_such_user", "whatever"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("改资料走真实 UPDATE：只改传入的字段")
    void updateProfileWritesOnlyProvidedFields() {
        User registered = userService.register("it_eve", "raw-pw-12345", "伊芙");
        Long id = registered.getId();

        userService.updateProfile(id, "新昵称", null, null);

        User loaded = userMapper.selectById(id);
        assertThat(loaded.getNickname()).isEqualTo("新昵称");
        assertThat(loaded.getUsername()).isEqualTo("it_eve");   // 没被改
    }
}
