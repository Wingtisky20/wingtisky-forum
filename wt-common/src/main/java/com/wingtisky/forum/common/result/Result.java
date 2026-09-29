package com.wingtisky.forum.common.result;

import com.wingtisky.forum.common.trace.TraceIdHolder;

/**
 * 统一响应体。**所有接口的返回都是这个形状**，不例外。
 *
 * <p><b>为什么所有接口都要包一层</b>：如果有的接口直接返回实体、有的返回 {@code Result}，
 * 前端就得为每个接口单独判断"这次到底怎么取数据"。统一之后，前端的成功/失败处理只写一次。
 *
 * <p>HTTP 状态码仍然照常使用（401 / 403 / 429 等），{@code code} 是**业务**层面的细分。
 * 两者不冲突：状态码给通用网关和浏览器看，{@code code} 给业务前端看。
 *
 * @param <T> 业务数据的类型
 */
public class Result<T> {

    /** 成功的码值。用字符串而不是 0，是为了和 {@link ErrorCode} 的格式保持一致。 */
    public static final String SUCCESS_CODE = "0";

    private String code;
    private String message;
    private T data;
    private String traceId;

    /** 给 Jackson 反序列化用。 */
    protected Result() {
    }

    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        // 从日志上下文取，保证"响应里的 traceId"和"日志里的 traceId"是同一个
        this.traceId = TraceIdHolder.current();
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, "ok", data);
    }

    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, "ok", null);
    }

    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 用自定义文案覆盖默认文案。
     *
     * <p><b>谨慎使用</b>：多数情况下应直接用 {@link ErrorCode} 的默认文案——文案统一
     * 才好排查，也避免把不该说的细节（比如"用户 ID 3 不存在"）漏给调用方。
     */
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }
}
