package com.wingtisky.forum.forum.user.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wingtisky.forum.forum.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住"用户资料响应里绝不出现密码"这个契约。
 *
 * <p><b>为什么值得单独写一条测试</b>：这是整个用户域里**最容易静默出错**的地方。
 * 谁把 {@code User} 实体直接返回出去，功能照常工作、测试照常绿，
 * 只是每个查询都会把密码哈希吐给前端——而且不会有任何报错提示。
 * 这类错误靠自觉是防不住的，只能靠一条会失败的测试。
 *
 * <p>这里测的是 DTO 的序列化结果而不是 HTTP 层：DTO 正是被序列化的那个对象，
 * 因此它包含哪些字段就等于响应里有哪些字段。跑得快，也不需要起 Spring 上下文。
 * HTTP 层另有一条集成测试断言同样的契约（防的是"Controller 没转 DTO"）。
 */
class UserProfileResponseTest {

    /**
     * 用 Spring 的构造器而不是 {@code new ObjectMapper()}。
     *
     * <p>裸的 ObjectMapper **不注册 JavaTimeModule**，序列化 {@code LocalDateTime} 会直接抛
     * {@code InvalidDefinitionException}。而真实应用里 Spring Boot 自动配置的 ObjectMapper
     * 是带这个模块的——测试若用裸对象，测的就不是生产环境的行为。
     * （这个坑在本测试的第一版里真实出现过：3 条测试错了 2 条。）
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("序列化结果里不含 password 字段，也不含哈希值本身")
    void neverExposesPassword() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setNickname("爱丽丝");
        user.setPassword("$2a$10$SUPER-SECRET-HASH-VALUE");
        user.setStatus(User.STATUS_ACTIVE);
        user.setDeleted(0);
        user.setCreateTime(LocalDateTime.now());

        String json = objectMapper.writeValueAsString(UserProfileResponse.from(user));

        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("SUPER-SECRET-HASH-VALUE");
        // 内部状态字段同样不该出去
        assertThat(json).doesNotContain("deleted");
        assertThat(json).doesNotContain("status");
    }

    @Test
    @DisplayName("邮箱不在公开资料里——别人看你的主页时不该看到它")
    void doesNotExposeEmail() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        String json = objectMapper.writeValueAsString(UserProfileResponse.from(user));

        assertThat(json).doesNotContain("email");
        assertThat(json).doesNotContain("alice@example.com");
    }

    @Test
    @DisplayName("该有的字段一个都不能少——防止有人为了'安全'把字段删过头")
    void keepsExpectedFields() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("bob");
        user.setNickname("鲍勃");
        user.setAvatar("https://example.com/a.png");
        user.setCreateTime(LocalDateTime.of(2026, 9, 29, 12, 0));

        String json = objectMapper.writeValueAsString(UserProfileResponse.from(user));

        assertThat(json).contains("\"id\":7");
        assertThat(json).contains("\"username\":\"bob\"");
        assertThat(json).contains("\"nickname\":\"鲍勃\"");
        assertThat(json).contains("\"avatar\"");
    }
}
