package com.wingtisky.forum.forum.user.entity;

/**
 * 角色码。对应 {@code t_role.code}。
 *
 * <p><b>为什么用枚举而不是一串 String 常量</b>：角色名会被用在两处——
 * 数据库里（存字符串）和 Spring Security 的权限判断里（{@code hasRole('ADMIN')}）。
 * 用常量时，两处各写一遍字面量，拼错任何一个都不会报错，只会表现为
 * "这个人明明有权限却说没权限"。枚举把拼写错误变成编译错误。
 *
 * <p>只有三个角色，与术语表（{@code docs/01-requirements/glossary.md}）一致，
 * **不加第四个**；spec §1.5 明确不做复杂权限矩阵。
 *
 * <p><b>与 Spring Security 的约定</b>：{@code hasRole('ADMIN')} 实际比对的是
 * 权限字符串 {@code ROLE_ADMIN}——Spring Security 会自动加上 {@code ROLE_} 前缀。
 * 因此构造 {@code GrantedAuthority} 时必须写 {@code ROLE_ + code()}，
 * 少了前缀的表现是"所有角色判断都失败"。
 */
public enum RoleCode {

    /** 注册后的默认角色。 */
    USER,

    /** 版主，可管理内容。 */
    MODERATOR,

    /** 管理员，全部权限。 */
    ADMIN
}
