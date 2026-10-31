package com.planb.emotionfriend.api.auth;

import com.planb.emotionfriend.api.common.idempotency.IdempotencyProperties;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthConfiguration.Properties.class)
public class AuthConfiguration {
    public AuthConfiguration(Properties properties, IdempotencyProperties idempotency) {
        byte[] key = properties.keyBytes();
        if (MessageDigest.isEqual(key, Base64.getDecoder().decode(idempotency.fingerprintKey()))
                || MessageDigest.isEqual(key, Base64.getDecoder().decode(idempotency.encryptionKey()))) {
            throw new IllegalArgumentException("JWT and idempotency keys must be independent");
        }
    }

    @ConfigurationProperties("emotion-friend.auth")
    public record Properties(String signingKey, @DefaultValue("emotion-friend") String issuer,
                             @DefaultValue("emotion-friend-adult") String audience,
                             @DefaultValue("PT10M") Duration accessTtl, @DefaultValue("P30D") Duration sessionTtl) {
        public Properties {
            decode(signingKey);
            if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                    || accessTtl == null || accessTtl.compareTo(Duration.ofSeconds(30)) < 0
                    || accessTtl.compareTo(Duration.ofMinutes(30)) > 0 || sessionTtl == null
                    || sessionTtl.compareTo(accessTtl) < 0 || sessionTtl.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException("Invalid authentication configuration");
            }
        }
        public byte[] keyBytes() { return decode(signingKey); }
        private static byte[] decode(String key) {
            try {
                byte[] decoded = Base64.getDecoder().decode(key == null ? "" : key);
                if (decoded.length != 32) throw new IllegalArgumentException();
                return decoded;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("EF_JWT_SIGNING_KEY must be a separate random 32-byte Base64 secret");
            }
        }
        @Override public String toString() { return "AuthProperties[redacted]"; }
    }
}
