package com.wingtisky.forum.common.exception;

import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.common.trace.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理的验证。
 *
 * <p>用 MockMvc 的 standalone 模式——只装一个测试控制器与
 * {@link GlobalExceptionHandler}，不起整个 Spring 上下文。理由：这里要验的是
 * "异常 → 响应体"的映射本身，带上数据库、Redis 只会让测试变慢且不稳定。
 *
 * <p><b>为什么这些断言值得写</b>：这些映射是**所有接口**共用的。改错一处，
 * 全站所有接口的错误格式一起错，而这类错误在正常路径的测试里根本发现不了。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("业务异常：用 ErrorCode 自带的 HTTP 状态，响应体是统一格式")
    void bizExceptionMapsToItsOwnHttpStatus() throws Exception {
        mockMvc.perform(get("/test/biz"))
                .andExpect(status().isConflict())                 // USERNAME_EXISTS → 409
                .andExpect(jsonPath("$.code").value("A0101"))
                .andExpect(jsonPath("$.message").value(ErrorCode.USERNAME_EXISTS.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("越权异常映射成 403，而不是掉进兜底分支变成 500")
    void accessDeniedMapsTo403NotInternalError() throws Exception {
        mockMvc.perform(get("/test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0003"));
    }

    @Test
    @DisplayName("未预期异常：对外只说'系统繁忙'，绝不泄露内部细节")
    void unexpectedExceptionDoesNotLeakInternals() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("A0005"))
                .andExpect(jsonPath("$.message").value(ErrorCode.SYSTEM_ERROR.getMessage()))
                // 这条断言是重点：异常信息里写了内部细节，响应里一个字都不能出现
                .andExpect(jsonPath("$.message", not(containsString("内部细节"))));
    }

    @Test
    @DisplayName("参数校验失败：指出是哪个字段，并说明原因")
    void validationFailureNamesTheOffendingField() throws Exception {
        mockMvc.perform(post("/test/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"nickname\":\"这个太长了超过十个字符了\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0001"))
                .andExpect(jsonPath("$.message", containsString("username")))
                .andExpect(jsonPath("$.message", containsString("nickname")));
    }

    @Test
    @DisplayName("traceId 出现在响应头里，用户报障时可以直接截图")
    void traceIdIsReturnedAsHeader() throws Exception {
        mockMvc.perform(get("/test/biz"))
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER));
    }

    // ---------- 测试用的最小控制器：只负责抛异常 ----------

    @RestController
    static class ThrowingController {

        @GetMapping("/test/biz")
        void biz() {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }

        @GetMapping("/test/denied")
        void denied() {
            throw new AccessDeniedException("测试用：拒绝");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("测试用：内部细节不该外泄");
        }

        @PostMapping("/test/valid")
        void valid(@Valid @RequestBody SampleRequest request) {
            // 走到这里说明校验通过了；本测试只关心校验失败的分支
        }
    }

    record SampleRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @Size(max = 10, message = "昵称最长 10 个字符") String nickname) {
    }
}
