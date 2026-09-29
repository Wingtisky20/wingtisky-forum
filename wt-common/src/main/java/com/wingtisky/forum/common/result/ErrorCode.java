package com.wingtisky.forum.common.result;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。
 *
 * <p><b>分段规则</b>（见 {@code docs/03-design/m1-user-and-security.md} §6.2）：
 * <ul>
 *   <li>{@code A00xx} —— 通用（参数、认证、权限、限流、系统）</li>
 *   <li>{@code A01xx} —— 用户域</li>
 * </ul>
 * 后续里程碑按域续段（内容域 A02xx、交易域 A03xx、秒杀域 A04xx）。
 *
 * <p><b>为什么码值带字母前缀而不是纯数字</b>：分段码可读、可 grep。线上出问题时
 * {@code grep 'A01'} 能一次捞出用户域的全部报错，纯数字做不到。
 *
 * <p><b>为什么错误码里带 HTTP 状态</b>：这是 API 契约的一部分——前端要区分
 * "401 该去登录"和"403 是真的没权限"。放在这里而不是散在各处的 if 里，
 * 是因为散着写迟早会漏一个，而漏掉的表现是"该 403 的返回了 500"。
 */
public enum ErrorCode {

    // ---------- A00xx 通用 ----------

    /** 参数校验不通过。由 {@code @Valid} 触发。 */
    PARAM_INVALID("A0001", "参数不合法", HttpStatus.BAD_REQUEST),

    /** 未登录，或令牌已过期/非法。 */
    UNAUTHORIZED("A0002", "未登录或登录已过期", HttpStatus.UNAUTHORIZED),

    /** 已登录但没有权限，或访问的不是自己的资源。 */
    FORBIDDEN("A0003", "没有权限", HttpStatus.FORBIDDEN),

    /** 触发限流。 */
    TOO_MANY_REQUESTS("A0004", "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),

    /** 兜底：未预料到的异常。对外只暴露这个码，细节看日志。 */
    SYSTEM_ERROR("A0005", "系统繁忙，请稍后再试", HttpStatus.INTERNAL_SERVER_ERROR),

    // ---------- A01xx 用户域 ----------

    USERNAME_EXISTS("A0101", "用户名已被占用", HttpStatus.CONFLICT),

    /**
     * 登录失败。
     *
     * <p><b>注意：这个码同时用于"用户名不存在"和"密码错误"，是有意为之。</b>
     * 若两者返回不同的错误码或文案，接口就变成了**用户名探测器**——攻击者
     * 可以逐个试出哪些用户名真实存在，再对它们集中撞库。宁可让用户多打一次
     * 字，也不给攻击者这个信息。
     */
    LOGIN_FAILED("A0102", "用户名或密码错误", HttpStatus.UNAUTHORIZED),

    USER_NOT_FOUND("A0103", "用户不存在", HttpStatus.NOT_FOUND),

    /** 账号被管理员禁用。与"密码错误"区分，因为这是用户自己无法解决的问题。 */
    ACCOUNT_DISABLED("A0104", "账号已被禁用", HttpStatus.FORBIDDEN),

    ;

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
