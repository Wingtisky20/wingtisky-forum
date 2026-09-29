package com.wingtisky.forum.forum.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>用 record 而不是实体：请求体是 **API 契约**，与数据库表结构是两回事。
 * 直接拿实体接请求的后果是"前端能提交的字段"被动地等于"表里所有的列"——
 * 于是有人传个 {@code status=1} 就能把账号设成禁用状态。
 *
 * <p>校验注解在这里而不是在 Service：格式问题（太短、含非法字符）属于接口边界，
 * 拦在最外面，Service 就不必反复写防御代码，也能被非 HTTP 入口复用。
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需在 3~32 之间")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "用户名只能包含字母、数字、下划线、连字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需在 8~64 之间")
        String password,

        @NotBlank(message = "昵称不能为空")
        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname
) {
}
