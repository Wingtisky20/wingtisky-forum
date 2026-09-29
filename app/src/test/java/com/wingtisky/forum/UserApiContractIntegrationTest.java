package com.wingtisky.forum;

import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层的契约测试：**走完整的 Spring 上下文与安全过滤器链**。
 *
 * <p><b>与单元测试的分工</b>：DTO 自身的序列化由
 * {@code UserProfileResponseTest} 覆盖（快、不依赖环境）。这里验的是它覆盖不到的那些——
 * Controller **有没有真的用 DTO**、安全过滤器链的放行规则、异常是否走了统一处理。
 * 这三类问题都只在"整个应用装起来"时才存在。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class UserApiContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Test
    @DisplayName("公开资料接口的响应里不含 password —— 防的是 Controller 直接返回实体")
    void profileResponseNeverLeaksPassword() throws Exception {
        User user = userService.register("api_contract_a", "pw-12345678", "契约测试");

        MvcResult result = mockMvc.perform(get("/api/users/{id}", user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("api_contract_a"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("$2a$");
    }

    @Test
    @DisplayName("查不存在的用户 → 404 且是统一响应体（不是 Spring 的默认错误页）")
    void unknownUserReturnsUnifiedNotFound() throws Exception {
        mockMvc.perform(get("/api/users/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A0103"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("参数不合法 → 400，且 message 指出是哪个字段")
    void invalidRegisterInputNamesTheField() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ab","password":"pw-12345678","nickname":"太短"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0001"))
                .andExpect(jsonPath("$.message", containsString("username")));
    }

    @Test
    @DisplayName("受保护接口不带令牌 → 401 且是统一响应体")
    void protectedEndpointWithoutTokenReturnsUnifiedUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0002"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("登录失败的响应里不能出现任何暗示用户名是否存在的差异")
    void loginFailureDoesNotRevealWhetherUserExists() throws Exception {
        userService.register("api_contract_b", "pw-12345678", "存在的人");

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"api_contract_b","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String noSuchUser = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"api_contract_nobody","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // 两者的 code 与 message 必须完全一致。traceId 不同是正常的（每次请求都不同），
        // 所以只比对 code 与 message 两个字段。
        assertThat(codeAndMessageOf(wrongPassword)).isEqualTo(codeAndMessageOf(noSuchUser));
        assertThat(wrongPassword).doesNotContain("api_contract_b");
        assertThat(noSuchUser).doesNotContain("api_contract_nobody");
    }

    @Test
    @DisplayName("响应体不含 deleted/status 这类内部字段")
    void responseDoesNotExposeInternalFields() throws Exception {
        User user = userService.register("api_contract_c", "pw-12345678", "内部字段");

        mockMvc.perform(get("/api/users/{id}", user.getId()))
                .andExpect(content().string(not(containsString("\"deleted\""))))
                .andExpect(content().string(not(containsString("\"status\""))));
    }

    /** 从响应 JSON 里抠出 code 与 message 两段（用字符串处理避免引入额外依赖）。 */
    private String codeAndMessageOf(String json) {
        return json.replaceAll(".*\"code\"", "\"code\"").replaceAll("\"traceId\".*", "");
    }
}
