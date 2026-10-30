package com.planb.emotionfriend.api.common.idempotency;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("emotion-friend.idempotency")
public record IdempotencyProperties(String fingerprintKey, String encryptionKey,
                                   @DefaultValue("1048576") int maxResponseBytes) {
    public IdempotencyProperties {
        byte[] fingerprint = decode(fingerprintKey);
        byte[] encryption = decode(encryptionKey);
        if (java.security.MessageDigest.isEqual(fingerprint, encryption)) {
            throw new IllegalArgumentException("Idempotency fingerprint and encryption keys must differ");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 16_777_216) {
            throw new IllegalArgumentException("Invalid idempotency response size limit");
        }
    }

    static byte[] decode(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value == null ? "" : value);
            if (bytes.length != 32) throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency keys must be explicitly configured as 32-byte Base64 secrets");
        }
    }

    @Override public String toString() { return "IdempotencyProperties[redacted]"; }
}
