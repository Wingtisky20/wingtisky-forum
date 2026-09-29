package com.wingtisky.forum.forum.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 修改资料的请求。
 *
 * <p><b>字段全部可选，但至少要给一个</b>——"一个都没给"的校验放在 Service 里做
 * （见 {@code UserService.updateProfile}），因为那不只是格式问题：
 * 全部为 null 时动态 SQL 会生成空的 SET 子句，那是语法错误。
 *
 * <p>注意这里**没有 username / password / status**：这三个字段不存在于本 DTO 中，
 * 因此无论前端传什么都会被忽略。靠"Service 里记得别更新它们"是不可靠的，
 * 靠"请求体里根本没有这个字段"才是结构性的保证。
 */
public record UpdateProfileRequest(

        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname,

        @Size(max = 255, message = "头像地址过长")
        String avatar,

        @Email(message = "邮箱格式不正确")
        @Size(max = 64, message = "邮箱过长")
        String email
) {
}
