package com.planb.emotionfriend.api.common;

import static org.assertj.core.api.Assertions.*;

import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.IdempotencyProperties;
import com.planb.emotionfriend.api.common.idempotency.ReplayCipher;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.common.validation.RequestPreconditions;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FoundationPrimitivesTest {
    private static final String FINGERPRINT_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString("1".repeat(32).getBytes(StandardCharsets.US_ASCII));

    @Test
    void replayCipherBindsResponseToScopeAndAuthenticatesBytes() {
        ReplayCipher cipher = new ReplayCipher(new IdempotencyProperties(FINGERPRINT_KEY, ENCRYPTION_KEY, 1024));
        byte[] plaintext = "synthetic-credential".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = cipher.encrypt(plaintext, "scope:200:application/json");
        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1)).doesNotContain("synthetic-credential");
        assertThat(cipher.decrypt(encrypted, "scope:200:application/json")).isEqualTo(plaintext);
        assertThat(cipher.encrypt(plaintext, "scope:200:application/json")).isNotEqualTo(encrypted);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "other-scope:200:application/json")).isInstanceOf(IllegalStateException.class);
        encrypted[encrypted.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "scope:200:application/json")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingOrReusedSecretConfigurationFailsClosed() {
        assertThatThrownBy(() -> new IdempotencyProperties("", ENCRYPTION_KEY, 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyProperties(FINGERPRINT_KEY, FINGERPRINT_KEY, 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IdempotencyProperties(FINGERPRINT_KEY, ENCRYPTION_KEY, 1024).toString())
                .doesNotContain(FINGERPRINT_KEY, ENCRYPTION_KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "a\nb"})
    void invalidIdempotencyKeyIsRejected(String key) {
        assertThatThrownBy(() -> RequestPreconditions.idempotencyKey(key)).isInstanceOf(ApiException.class);
    }

    @Test
    void idempotencyKeyHas256CodePointLimit() {
        assertThat(RequestPreconditions.idempotencyKey("a".repeat(256))).hasSize(256);
        assertThatThrownBy(() -> RequestPreconditions.idempotencyKey("a".repeat(257))).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"3", "W/\"3\"", "\"-1\"", "\"9999999999999999999\""})
    void ifMatchMustBeQuotedRevision(String value) {
        assertThatThrownBy(() -> RequestPreconditions.checkpointRevision(value, 3)).isInstanceOf(ApiException.class);
    }

    @Test
    void staleBodyRevisionIsRejected() {
        assertThat(RequestPreconditions.checkpointRevision("\"3\"", 3)).isEqualTo(3);
        assertThatThrownBy(() -> RequestPreconditions.checkpointRevision("\"3\"", 2))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.STALE_CHECKPOINT));
    }

    @Test
    void recoveryErrorsCannotBeCreatedWithoutTheirRequiredDetails() {
        assertThatThrownBy(() -> new ApiException(ErrorCode.CHECKPOINT_EXPIRED, ErrorDetails.checkpoint(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adultIdentityCannotBeReusedAsChildRuntime() {
        assertThatThrownBy(() -> new ActorPrincipal("actor", ActorPrincipal.Kind.ADULT, "child", "device", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
