package com.wingtisky.forum.forum.user.entity;

import java.time.LocalDateTime;

/**
 * 角色实体，对应表 {@code t_role}。
 *
 * <p>角色共三个（{@code USER} / {@code MODERATOR} / {@code ADMIN}），由建表脚本
 * 初始化，运行时不增删。因此这个实体不需要写操作，见 {@code RoleMapper}。
 */
public class Role {

    private Long id;
    private String code;
    private String name;
    private String description;
    private LocalDateTime createTime;

    /** 角色码转成枚举；值非法时返回 {@code null}（DB 里存在未知角色说明数据被改过）。 */
    public RoleCode codeAsEnum() {
        if (code == null) {
            return null;
        }
        try {
            return RoleCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
