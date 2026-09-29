package com.wingtisky.forum.common.result;

import com.wingtisky.forum.common.trace.TraceIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("成功响应：code 为 0，携带数据")
    void successCarriesData() {
        Result<String> result = Result.success("hello");

        assertThat(result.getCode()).isEqualTo(Result.SUCCESS_CODE);
        assertThat(result.getData()).isEqualTo("hello");
    }

    @Test
    @DisplayName("失败响应：code 与文案来自 ErrorCode，且不带数据")
    void errorUsesErrorCode() {
        Result<Void> result = Result.error(ErrorCode.FORBIDDEN);

        assertThat(result.getCode()).isEqualTo("A0003");
        assertThat(result.getMessage()).isEqualTo(ErrorCode.FORBIDDEN.getMessage());
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("traceId 取自日志上下文——保证响应里的与日志里的是同一个")
    void traceIdComesFromMdc() {
        MDC.put(TraceIdHolder.MDC_KEY, "abc123");

        assertThat(Result.success().getTraceId()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("不在请求上下文中时不抛异常，traceId 为 null")
    void traceIdIsNullOutsideRequestContext() {
        assertThat(Result.success().getTraceId()).isNull();
    }

    @Test
    @DisplayName("登录失败的错误码同时覆盖'用户名不存在'与'密码错误'")
    void loginFailureDoesNotRevealWhetherUsernameExists() {
        // 这条测试是**故意**的：它把"不能区分这两种失败"钉成契约。
        // 一旦有人为了"体验更好"给它们各自加了错误码，这条会失败并提醒他读注释。
        assertThat(ErrorCode.LOGIN_FAILED.getCode()).isEqualTo("A0102");
        assertThat(ErrorCode.LOGIN_FAILED.getMessage()).isEqualTo("用户名或密码错误");
    }
}
