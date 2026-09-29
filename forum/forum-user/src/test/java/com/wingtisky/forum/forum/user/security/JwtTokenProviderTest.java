package com.wingtisky.forum.forum.user.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌签发的单元测试。**不依赖 Redis、不依赖 MySQL**，因此 CI 也能跑。
 *
 * <p>密码学代码值得单独测：它出错的方式是"功能看起来正常，但保护没了"。
 */
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-32-bytes-long";

    private JwtTokenProvider providerWith(Duration accessTtl) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setAccessTtl(accessTtl);
        properties.setRefreshTtl(Duration.ofDays(7));
        return new JwtTokenProvider(properties);
    }

    @Test
    @DisplayName("签发的访问令牌能解析回来，用户标识与角色都在")
    void issuedAccessTokenRoundTrips() {
        JwtTokenProvider provider = providerWith(Duration.ofMinutes(30));

        IssuedToken issued = provider.issueAccessToken(42L, "alice", List.of("USER", "ADMIN"));
        Claims claims = provider.parse(issued.token());

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.get(JwtTokenProvider.CLAIM_ROLES, List.class))
                .containsExactly("USER", "ADMIN");
        assertThat(claims.getId()).isEqualTo(issued.jti());
    }

    @Test
    @DisplayName("每次签发的 jti 都不同——它是刷新令牌可撤销性的依据")
    void jtiIsUniquePerIssue() {
        JwtTokenProvider provider = providerWith(Duration.ofMinutes(30));

        String first = provider.issueRefreshToken(1L).jti();
        String second = provider.issueRefreshToken(1L).jti();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("过期的令牌解析时抛异常，而不是安静地返回一个过期载荷")
    void expiredTokenIsRejected() throws InterruptedException {
        // 负的有效期 → 签发出来就已经过期
        JwtTokenProvider provider = providerWith(Duration.ofSeconds(-1));
        String token = provider.issueAccessToken(1L, "alice", List.of("USER")).token();

        assertThatThrownBy(() -> provider.parse(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("换一把密钥签的令牌验不过——签名校验真的在起作用")
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtTokenProvider issuer = providerWith(Duration.ofMinutes(30));

        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("a-completely-different-secret-key-32-bytes-min");
        JwtTokenProvider verifier = new JwtTokenProvider(otherProperties);

        String foreignToken = issuer.issueAccessToken(1L, "alice", List.of("USER")).token();

        assertThatThrownBy(() -> verifier.parse(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("篡改载荷（把 USER 改成 ADMIN）验不过——签名覆盖了整个载荷")
    void tamperedPayloadIsRejected() {
        JwtTokenProvider provider = providerWith(Duration.ofMinutes(30));
        String token = provider.issueAccessToken(1L, "alice", List.of("USER")).token();

        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                        .replace("USER", "ADMIN").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> provider.parse(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("密钥短于 256 位时直接拒绝——**在启动期失败，而不是等被穷举出来**")
    void weakKeyIsRejectedAtConstruction() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("too-short");

        assertThatThrownBy(() -> new JwtTokenProvider(properties))
                .isInstanceOf(WeakKeyException.class);
    }

    @Test
    @DisplayName("刷新令牌的载荷里不放角色")
    void refreshTokenCarriesNoRoles() {
        JwtTokenProvider provider = providerWith(Duration.ofMinutes(30));

        Claims claims = provider.parse(provider.issueRefreshToken(1L).token());

        // 刷新时重新查库取角色，取到的是最新的；写进令牌反而会固化一个可能已过期的角色
        assertThat(claims.get("roles")).isNull();
        assertThat(claims.get("username")).isNull();
    }
}
