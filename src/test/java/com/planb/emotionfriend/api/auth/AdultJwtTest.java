package com.planb.emotionfriend.api.auth;

import static org.assertj.core.api.Assertions.*;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class AdultJwtTest {
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final UUID MEMBER = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final String KEY = Base64.getEncoder().encodeToString("s".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));

    private AuthConfiguration.Properties properties(String key, String issuer, String audience) {
        return new AuthConfiguration.Properties(key, issuer, audience, Duration.ofMinutes(10), Duration.ofDays(30));
    }
    private AdultJwt jwt(Instant now) { return new AdultJwt(properties(KEY, "emotion-friend", "emotion-friend-adult"), Clock.fixed(now, ZoneOffset.UTC)); }

    @Test
    void signatureExpiryAndSessionIdentityAreVerified() {
        var token = jwt(NOW).issue(MEMBER, SESSION, NOW.plus(Duration.ofDays(30)));
        assertThat(jwt(NOW).verify(token.accessToken())).isEqualTo(new AdultJwt.Verified(MEMBER, SESSION));
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThatThrownBy(() -> jwt(NOW.plusSeconds(600)).verify(token.accessToken()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.TOKEN_EXPIRED));
        assertThatThrownBy(() -> jwt(NOW.minusSeconds(1)).verify(token.accessToken())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> jwt(NOW).verify(token.accessToken() + "x")).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "purpose", "subject", "session", "expiration", "issuedAt", "notBefore", "longLifetime"})
    void signedButInvalidClaimsAreRejected(String invalid) {
        var claims = JwtClaimsSet.builder().issuer(invalid.equals("issuer") ? "other" : "emotion-friend")
                .audience(List.of(invalid.equals("audience") ? "child" : "emotion-friend-adult"))
                .subject(invalid.equals("subject") ? "invalid" : MEMBER.toString()).id(UUID.randomUUID().toString())
                .claim("sid", invalid.equals("session") ? "invalid" : SESSION.toString())
                .claim("kind", invalid.equals("purpose") ? "CHILD" : "ADULT");
        if (!invalid.equals("issuedAt")) claims.issuedAt(NOW);
        if (!invalid.equals("notBefore")) claims.notBefore(NOW);
        if (!invalid.equals("expiration")) claims.expiresAt(NOW.plusSeconds(invalid.equals("longLifetime") ? 601 : 600));
        var encoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(Base64.getDecoder().decode(KEY), "HmacSHA256")).build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        assertThatThrownBy(() -> jwt(NOW).verify(value)).isInstanceOf(ApiException.class);
    }

    @Test
    void wrongKeyAndUnsignedTokensAreRejected() {
        String otherKey = Base64.getEncoder().encodeToString("k".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var other = new AdultJwt(properties(otherKey, "emotion-friend", "emotion-friend-adult"), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> jwt(NOW).verify(other.issue(MEMBER, SESSION, NOW.plusSeconds(600)).accessToken())).isInstanceOf(ApiException.class);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"admin\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> jwt(NOW).verify("eyJhbGciOiJub25lIn0." + payload + ".")).isInstanceOf(ApiException.class);
    }

    @Test
    void accessExpiryCannotExceedAbsoluteSessionExpiryAndSecretsAreRedacted() {
        var token = jwt(NOW).issue(MEMBER, SESSION, NOW.plusSeconds(40));
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(40));
        assertThat(token.toString()).doesNotContain(token.accessToken());
        assertThat(properties(KEY, "issuer", "audience").toString()).doesNotContain(KEY);
        assertThat(new AuthContracts.RefreshRequest("secret").toString()).doesNotContain("secret");
    }

    @Test
    void missingAndMalformedKeysFailClosed() {
        for (String key : List.of("", "invalid", Base64.getEncoder().encodeToString(new byte[16]))) {
            assertThatThrownBy(() -> properties(key, "issuer", "audience")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
