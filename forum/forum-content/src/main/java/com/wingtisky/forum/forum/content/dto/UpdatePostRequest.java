package com.wingtisky.forum.forum.content.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

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
        String content,

        /**
         * 新的标签列表。{@code null} 表示**不改标签**；给了就**整体替换**
         * （与"改了正文、但标签不动"这种部分更新不冲突，因为两者的判据都是"给没给"）。
         *
         * <p>传空列表是合法的，表示"把这篇帖子的标签全去掉"——这和"不改"是两件事，
         * 所以不能用"列表是否为空"来判断要不要处理。
         */
        List<String> tags
) {
}
