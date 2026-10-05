package com.wingtisky.forum.forum.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发帖请求。
 *
 * <p><b>长度上限不是"防用户手滑"，是防资源被拖垮。</b>
 * 正文存的是 MEDIUMTEXT（能装 16MB），不设上限的话，一个 10MB 的正文请求
 * 就足以让服务难受很久——**而它看起来完全合法**。
 * 10 万字（约 300KB）对技术长文来说绰绰有余。
 *
 * <p>上限与数据库列长的关系：{@code title} 对应 {@code varchar(100)}——
 * 校验与建表脚本必须一致，否则超长标题会在数据库层报错，
 * 而那时错误信息是"Data too long for column"，离用户看到的字段已经隔了一层。
 *
 * @param title   标题，不能为空白，最多 100 字
 * @param content 正文（Markdown 文本），不能为空白，最多 10 万字
 */
public record CreatePostRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 100, message = "标题最多 100 个字")
        String title,

        @NotBlank(message = "正文不能为空")
        @Size(max = 100_000, message = "正文最多 10 万字")
        String content,

        /**
         * 标签名。**可以为空或 {@code null}**——不带标签的帖子是允许的。
         *
         * <p>这里**没有**加校验注解：标签的长度、数量、去重规则都与数据库列长
         * 绑在一起（{@code t_tag.name} 是 {@code varchar(32)}、单帖最多 5 个），
         * 全部放在 {@code TagService.normalize} 一处。分两处写，
         * 迟早出现"接口说可以、数据库说不行"。
         */
        List<String> tags
) {
}
