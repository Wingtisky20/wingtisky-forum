package com.wingtisky.forum.forum.user.dto;

import jakarta.validation.constraints.NotBlank;

/** 用刷新令牌换新访问令牌的请求。 */
public record RefreshRequest(

        @NotBlank(message = "刷新令牌不能为空")
        String refreshToken
) {
}
