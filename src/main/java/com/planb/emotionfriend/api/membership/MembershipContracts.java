package com.planb.emotionfriend.api.membership;

import com.planb.emotionfriend.api.auth.AuthContracts.RequestedRole;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public final class MembershipContracts {
    private MembershipContracts() {}

    public record ReviewRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 200) String reviewReference) {}

    public record RoleChangeRequest(
            @NotNull RequestedRole role,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 200) String reviewReference) {}

    public record PasswordRequest(
            @NotNull
                    @Size(min = 1, max = 128)
                    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
                    String password) {
        @Override
        public String toString() {
            return "PasswordRequest[redacted]";
        }
    }

    public record VerifyEmailRequest(
            @NotNull UUID verificationId, @NotBlank @Size(max = 100) String token) {
        @Override
        public String toString() {
            return "VerifyEmailRequest[redacted]";
        }
    }

    public record Reauthentication(UUID verificationRef, Instant expiresAt) {}

    public record Accepted(String message) {
        public static Accepted generic() {
            return new Accepted("요청을 접수했습니다. 계정의 알림을 확인해 주세요.");
        }
    }
}
