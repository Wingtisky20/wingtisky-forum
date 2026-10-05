package com.wingtisky.forum.forum.content.dto;

import com.wingtisky.forum.forum.content.entity.Tag;

/**
 * 标签的返回形状。
 *
 * <p>只暴露 {@code id} / {@code name} / {@code postCount}——{@code t_tag} 表里
 * 也就这几列有意义。**不直接返回实体**：与帖子、用户同样的理由，
 * 实体是持久层对象，它加一个字段不该自动变成对外契约。
 */
public record TagView(Long id, String name, int postCount) {

    public static TagView from(Tag tag) {
        return new TagView(tag.getId(), tag.getName(), tag.getPostCount());
    }
}
