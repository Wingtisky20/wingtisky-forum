package com.wingtisky.forum.forum.user.entity;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 {@code t_user}。
 *
 * <p><b>这是持久层对象，不是 API 的返回形状。</b>Controller 必须把它转成 DTO 再返回——
 * 直接返回实体会把 {@code password} 一起吐给前端。这条不是"注意一下"，
 * 而是 Task 7 要用测试钉住的契约（断言响应 JSON 里不含 password 字段）。
 */
public class User {

    /** 正常。 */
    public static final int STATUS_ACTIVE = 0;

    /** 被管理员禁用。与"密码错误"区分：禁用是用户自己无法解决的问题。 */
    public static final int STATUS_DISABLED = 1;

    private Long id;
    private String username;

    /**
     * BCrypt 哈希（固定 60 字符），**永远不是明文**。
     *
     * <p>这一字段绝不允许出现在日志、响应体或异常信息里。若将来有人给实体加上
     * {@code toString()}，务必排除它——这也是"宁可手写 getter 也不用 Lombok
     * 全字段 toString"的原因之一。
     */
    private String password;

    private String nickname;
    private String avatar;
    private String email;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;

    /** 账号是否可用。 */
    public boolean isActive() {
        return status != null && status == STATUS_ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
