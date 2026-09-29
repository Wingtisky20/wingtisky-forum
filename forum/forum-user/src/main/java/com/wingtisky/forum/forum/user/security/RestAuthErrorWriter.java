package com.wingtisky.forum.forum.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把 Spring Security 的 401 / 403 也写成统一响应体。
 *
 * <p><b>不写它的后果</b>：认证失败发生在过滤器链里，**早于 DispatcherServlet**，
 * 因此 {@code @RestControllerAdvice} 根本拦不到——返回的是 Spring Security 的默认响应
 * （空 body，或一页 HTML）。于是前端要同时处理两种错误格式，而"统一响应体"这个
 * 契约在最常见的场景（没登录）上恰好失效。
 */
final class RestAuthErrorWriter {

    private RestAuthErrorWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        // charset 必须显式写：用 application/json 不带字符集时，
        // 某些客户端会按 ISO-8859-1 解析，中文文案变乱码
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // Result.error 会从 MDC 取 traceId —— TraceIdFilter 的 order 是
        // HIGHEST_PRECEDENCE，排在 Spring Security 过滤器链（-100）之前，
        // 所以这里拿到的是同一个 traceId，与日志对得上。
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(errorCode)));
    }
}
