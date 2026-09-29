package com.wingtisky.forum.forum.user.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 配置，绑定 {@code application.yml} 的 {@code wt.jwt.*}。
 *
 * <p><b>这是"策略"不是"机制"</b>——令牌活多久是业务语义，所以它落在业务模块
 * 而不是 {@code wt-infra}（architecture.md §4.3 的那条边界线）。
 *
 * @see JwtTokenProvider
 */
@ConfigurationProperties(prefix = "wt.jwt")
public class JwtProperties {

    /**
     * HS256 的签名密钥。**必须来自环境变量，绝不写进配置文件**（spec §10.9）。
     *
     * <p><b>长度至少 32 字节（256 位）</b>，否则 {@code Keys.hmacShaKeyFor} 会直接抛
     * {@code WeakKeyException} 拒绝启动——这是好事：短的 HMAC 密钥可以被暴力穷举，
     * 而"密钥太短"这种问题必须拦在启动期，不能等出事才发现。生成方式：
     * {@code openssl rand -base64 48}
     */
    private String secret;

    /**
     * 访问令牌有效期。
     *
     * <p>短是刻意的：access token **无法撤销**（无状态），所以只能靠"命短"来限制
     * 它被盗后的危害窗口。这个值与下面 refresh 的长寿命配合，构成设计 §3.2 的取舍：
     * <b>注销的实际效果 = 最长等这个时长</b>。
     */
    private Duration accessTtl = Duration.ofMinutes(30);

    /**
     * 刷新令牌有效期。存 Redis、可删除，因此可以长。
     */
    private Duration refreshTtl = Duration.ofDays(7);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }
}
