package com.planb.emotionfriend.api.common.error;

public record ApiError(ErrorCode code, String message, String traceId, boolean retryable, ErrorDetails details) {
}
