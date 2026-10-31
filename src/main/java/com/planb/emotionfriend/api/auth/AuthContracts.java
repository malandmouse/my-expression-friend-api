package com.planb.emotionfriend.api.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class AuthContracts {
    private AuthContracts() {}
    public enum RequestedRole { GUARDIAN, THERAPIST }
    public enum Role { GUARDIAN, THERAPIST, ADMIN }
    public enum Status { PENDING, ACTIVE, REJECTED, SUSPENDED }

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 80) String displayName,
            @NotNull @Size(min = 15, max = 128) @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
            @NotNull RequestedRole requestedRole) {
        @Override public String toString() { return "SignupRequest[redacted]"; }
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) @Schema(description = "가입 이메일") String loginId,
            @NotNull @Size(min = 1, max = 128) @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }

    public record RefreshRequest(@NotBlank @Size(max = 4096) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String refreshToken) {
        @Override public String toString() { return "RefreshRequest[redacted]"; }
    }

    public record Credential(String accessToken, Instant expiresAt) {
        @Override public String toString() { return "Credential[redacted]"; }
    }

    public record Credentials(Credential credential, String refreshToken) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemberView(UUID id, String email, String displayName, RequestedRole requestedRole,
                             Role role, Status status, boolean emailVerified, Instant createdAt) {}
}
