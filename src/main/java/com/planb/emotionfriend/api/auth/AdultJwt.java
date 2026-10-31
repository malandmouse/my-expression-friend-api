package com.planb.emotionfriend.api.auth;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class AdultJwt {
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final AuthConfiguration.Properties properties;
    private final Clock clock;

    public AdultJwt(AuthConfiguration.Properties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        var key = new SecretKeySpec(properties.keyBytes(), "HmacSHA256");
        encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Apply strict issuer, audience, purpose and time checks below using the application's Clock.
        decoder.setJwtValidator(jwt -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
    }

    public AuthContracts.Credential issue(UUID memberId, UUID sessionId, Instant sessionExpiry) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiry = now.plus(properties.accessTtl());
        if (expiry.isAfter(sessionExpiry)) expiry = sessionExpiry;
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject(memberId.toString()).id(UUID.randomUUID().toString()).issuedAt(now).notBefore(now).expiresAt(expiry)
                .claim("sid", sessionId.toString()).claim("kind", "ADULT").build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new AuthContracts.Credential(value, expiry);
    }

    public Verified verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            Instant now = clock.instant();
            if (!properties.issuer().equals(jwt.getClaimAsString("iss"))
                    || !List.of(properties.audience()).equals(jwt.getAudience())
                    || !"ADULT".equals(jwt.getClaimAsString("kind")) || jwt.getId() == null
                    || jwt.getIssuedAt() == null || jwt.getNotBefore() == null || jwt.getExpiresAt() == null
                    || jwt.getIssuedAt().isAfter(now) || jwt.getNotBefore().isAfter(now)
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(properties.accessTtl()))) {
                throw new ApiException(ErrorCode.AUTH_REQUIRED);
            }
            if (!jwt.getExpiresAt().isAfter(now)) throw new ApiException(ErrorCode.TOKEN_EXPIRED);
            return new Verified(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")));
        } catch (JwtException | IllegalArgumentException | NullPointerException exception) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
    }

    public record Verified(UUID memberId, UUID sessionId) {}
}
