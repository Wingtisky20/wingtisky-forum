package com.wingtisky.forum.forum.content.entity;

import java.time.LocalDateTime;

/**
 * 标签实体，对应表 {@code t_tag}。
 *
 * <p><b>标签由用户自由输入，不预置</b>：预置标签需要一个标签管理后台，
 * 而且用户想用的标签常常不在预置列表里。代价是要处理并发创建（见
 * {@code TagMapper} 与 {@code TagService}）。
 *
 * <p><b>不做小写化</b>：技术标签里 {@code Redis} 比 {@code redis} 好看，
 * 小写化会把这个信息永久丢掉。而 {@code t_tag.name} 上的唯一索引用的是
 * {@code utf8mb4} 的默认排序规则（大小写不敏感），所以 {@code Redis} 与 {@code redis}
 * 本来就会被判为同一个标签——**保留原始大小写，靠排序规则去重，两头的便宜都占**。
 */
public class Tag {

    private Long id;

    /** 标签名。唯一（大小写不敏感），最长 32 字符。 */
    private String name;

    /**
     * 该标签下**正常可见**的帖子数。冗余计数（ADR-0016）。
     *
     * <p>发帖 +1、删帖 -1、改帖换标签时旧的 -1 新的 +1——**三处都要记得**。
     * 漏一处不会报错，只会让标签页上写着"12 篇"、点进去只有 9 篇。
     */
    private Integer postCount;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPostCount() {
        return postCount;
    }

    public void setPostCount(Integer postCount) {
        this.postCount = postCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
