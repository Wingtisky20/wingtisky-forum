package com.wingtisky.forum.forum.user.security;

/**
 * 刚签发的令牌。
 *
 * <p>把 {@code jti} 一起返回，是因为刷新令牌的**可撤销性靠它**：
 * 服务端在 Redis 里记下"这个用户当前的 jti 是哪个"，注销时删掉。
 * 只返回 token 字符串的话，调用方还得再解析一次才能拿到 jti。
 *
 * @param token     令牌串（放在响应的 body 或 Header 里）
 * @param jti       令牌唯一标识（JWT 标准声明 {@code jti}）
 * @param expiresInSeconds 剩余有效秒数（回给前端，便于它提前刷新）
 */
public record IssuedToken(String token, String jti, long expiresInSeconds) {
}
