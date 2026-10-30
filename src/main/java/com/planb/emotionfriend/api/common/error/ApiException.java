package com.planb.emotionfriend.api.common.error;

import java.util.Objects;

public final class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final ErrorDetails details;

    public ApiException(ErrorCode code) {
        this(code, ErrorDetails.forError(code));
    }

    public ApiException(ErrorCode code, ErrorDetails details) {
        super(Objects.requireNonNull(code).name());
        this.code = code;
        this.details = Objects.requireNonNull(details);
        if (RecoveryResult.forError(code) != null && !ErrorDetails.forError(code).equals(details)) {
            throw new IllegalArgumentException("Recovery errors require their contract recovery details");
        }
    }

    public ErrorCode code() { return code; }
    public ErrorDetails details() { return details; }
}
