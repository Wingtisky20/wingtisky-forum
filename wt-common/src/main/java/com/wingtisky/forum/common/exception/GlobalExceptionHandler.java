package com.wingtisky.forum.common.exception;

import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常收敛成统一的 {@link Result}。
 *
 * <p><b>没有它会发生什么</b>：异常直接冒到容器，Spring 返回它自己的错误页
 * （{@code {"timestamp":...,"status":500,"path":...}} 或一整页 HTML）。于是前端要同时
 * 处理两种错误格式，而且**异常堆栈会直接吐给调用方**——那既是安全问题
 * （暴露包名、类名、SQL 片段），也是体验问题（用户看到一屏 Java 堆栈）。
 *
 * <p><b>三类异常分开处理，不是复制粘贴</b>：
 * <ul>
 *   <li>{@link BizException} —— 预期内的失败，**记 WARN 不记堆栈**</li>
 *   <li>参数校验失败 —— 把字段名和原因告诉前端，用户才知道改哪里</li>
 *   <li>{@link AccessDeniedException} —— 必须映射成 403，**不能让它变成 500**</li>
 *   <li>其余 —— 兜底。**日志里打完整堆栈，响应里只说"系统繁忙"**</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e) {
        ErrorCode errorCode = e.getErrorCode();
        // 预期内的失败，用 WARN 且不打堆栈——否则真正需要关注的技术故障会被淹没
        log.warn("业务异常: code={}, message={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(Result.error(errorCode, e.getMessage()));
    }

    /**
     * 请求体读不出来：JSON 语法错误、编码不是 UTF-8、类型对不上等。
     *
     * <p><b>必须单独接住，否则会掉进兜底分支报成 500。</b>这是**调用方的问题**，
     * 报 500 的后果不只是状态码难看：监控会把"有人发了个畸形请求"统计成
     * "服务端出错"，告警会被噪音淹没，真出事时反而看不见。
     *
     * <p><b>但响应里不说具体哪里错</b>：Jackson 的原始信息会带上期望的类名、
     * 字段路径等内部结构，对调用方没有价值，对探测者却是免费的线索。
     * 细节只进日志。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.error(ErrorCode.PARAM_INVALID, "请求体格式不正确"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        // 把出错的字段名拼进文案，前端/用户才知道改哪个输入框
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultMessageOf(fe))
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.error(ErrorCode.PARAM_INVALID, detail));
    }

    /**
     * 越权访问。
     *
     * <p><b>这个方法必须存在，而且必须映射成 403。</b>Spring Security 的
     * {@code @PreAuthorize} 拒绝时会抛 {@code AccessDeniedException}；若这里不接，
     * 它会走到兜底分支变成 500——于是"用户没权限"被报成"服务器出错了"，
     * 排查时会被引到完全错误的方向。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("越权访问被拒绝: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.getHttpStatus())
                .body(Result.error(ErrorCode.FORBIDDEN));
    }

    /**
     * 兜底。
     *
     * <p><b>注意这里的不对称</b>：日志里是完整堆栈，响应里只有一个笼统的"系统繁忙" +
     * traceId。堆栈只该给开发者看；调用方拿到 traceId，报障时把它给我们，就能在日志里
     * 精确定位。**把堆栈直接返回给调用方，等于把内部结构免费送给攻击者。**
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getHttpStatus())
                .body(Result.error(ErrorCode.SYSTEM_ERROR));
    }

    private String defaultMessageOf(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        return message == null ? "取值不合法" : message;
    }
}
