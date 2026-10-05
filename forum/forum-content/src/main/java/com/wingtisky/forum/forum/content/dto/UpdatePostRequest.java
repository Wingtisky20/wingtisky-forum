package com.wingtisky.forum.forum.content.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 改帖请求。**两个字段都是可选的**——{@code PATCH} 的语义就是"只改我给了的"。
 *
 * <p><b>为什么这里用 {@code @Pattern} 而不是 {@code @NotBlank}</b>：
 * {@code @NotBlank} 会把 {@code null} 也判为不合法（它检查的是"非 null 且非空白"），
 * 而这里 {@code null} 的语义是"**这一项不改**"，是合法的。
 * {@code @Pattern} 对 {@code null} 放行、对空串和纯空白串报错——
 * 正好是"要么不给，要么给个像样的"。
 *
 * <p>两个字段都不给时，请求本身能通过校验，但 {@code PostService.update} 会拒掉它——
 * 因为"什么也没改却返回成功"是个静默的谎，比报错更难查。
 *
 * @param title   新标题；{@code null} 表示不改
 * @param content 新正文；{@code null} 表示不改
 */
public record UpdatePostRequest(

        @Pattern(regexp = ".*\\S.*", message = "标题不能只有空白")
        @Size(max = 100, message = "标题最多 100 个字")
        String title,

        @Pattern(regexp = "(?s).*\\S.*", message = "正文不能只有空白")
        @Size(max = 100_000, message = "正文最多 10 万字")
        String content
) {
}
