package com.wingtisky.forum.forum.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p><b>这里刻意不校验密码长度</b>：注册时要求至少 8 位，但登录时不该重复这个规则——
 * 老用户的密码可能是在规则变更前设的。更重要的是，登录接口的校验信息会变成
 * **攻击者的探测工具**：如果登录时报"密码长度不足 8 位"，攻击者就知道
 * 输错格式和输错密码是两种反馈，多了一个可观察的维度。
 */
public record LoginRequest(

        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
