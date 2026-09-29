package com.wingtisky.forum.common.exception;

import com.wingtisky.forum.common.result.ErrorCode;

/**
 * 业务异常：**预期之内**的失败，比如"用户名已被占用""余额不足"。
 *
 * <p>与 {@code RuntimeException} 的区别就在"预期之内"这四个字：
 * <ul>
 *   <li>业务异常 —— 系统运转正常，只是这次操作不成立。**给用户看文案，不给开发者看堆栈**</li>
 *   <li>其他异常 —— 系统出问题了（空指针、连接超时）。**给开发者看堆栈，给用户只说"系统繁忙"**</li>
 * </ul>
 *
 * <p>把这两类分开，是为了让日志有信噪比：如果所有失败都打一屏堆栈，真正需要关注的
 * 技术故障就会被淹没在"用户又输错密码了"这类正常记录里。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 用自定义文案覆盖默认文案。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
