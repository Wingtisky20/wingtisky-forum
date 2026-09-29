package com.wingtisky.forum.forum.user.dto;

/**
 * 登录 / 刷新的结果。
 *
 * <p><b>这里是一处"Service 不返回实体"原则的例外，而且是有意的</b>：
 * 登录的结果本来就不是某个实体，而是"一对令牌 + 过期时间"这个组合概念。
 * 硬要拆成"Service 返回实体、Controller 组装"反而会造出一个没有业务含义的中间对象。
 *
 * <p>两个过期时间都回给前端，是为了让它能**提前刷新**而不是等 401 了再补救——
 * 后者会让用户看到一次失败的请求。
 *
 * @param accessToken     访问令牌，放进 {@code Authorization: Bearer <token>}
 * @param refreshToken    刷新令牌，只在换令牌时用
 * @param accessExpiresIn 访问令牌剩余秒数
 * @param refreshExpiresIn 刷新令牌剩余秒数
 */
public record LoginResult(
        String accessToken,
        String refreshToken,
        long accessExpiresIn,
        long refreshExpiresIn
) {
}
