package com.wingtisky.forum.forum.user.dto;

import com.wingtisky.forum.forum.user.entity.User;

import java.time.LocalDateTime;

/**
 * 用户资料的返回形状。
 *
 * <p><b>这个类存在的唯一理由是"不返回实体"。</b>User 实体里有 {@code password}
 * 和 {@code deleted} 这两个绝不该出现在响应里的字段——直接返回实体的后果是
 * **密码哈希会随每个用户查询一起吐给前端**，而且不会报任何错。
 *
 * <p>用 DTO 而不是给实体加 {@code @JsonIgnore}：后者把"序列化"这个 API 层的关切
 * 塞进了领域对象，且一旦有人换个序列化框架（或写 {@code toString()} 打日志），
 * 保护就失效了。DTO 是显式的白名单——只有列在这里的字段才会出去。
 *
 * <p><b>刻意不含 email</b>：这是"公开资料"的返回形状，别人看你的主页时不该看到
 * 你的邮箱。需要邮箱的场景（比如"我的资料"）用另一个 DTO，别在这里加字段——
 * 加了就等于默认公开。
 */
public record UserProfileResponse(
        Long id,
        String username,
        String nickname,
        String avatar,
        LocalDateTime createTime
) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatar(),
                user.getCreateTime());
    }
}
