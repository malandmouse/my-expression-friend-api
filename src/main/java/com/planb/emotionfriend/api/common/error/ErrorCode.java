package com.planb.emotionfriend.api.common.error;

/** Core 0.3.0 codes plus the documented local INTERNAL_ERROR extension. */
public enum ErrorCode {
    AUTH_REQUIRED(401), TOKEN_EXPIRED(401),
    CHILD_ACCESS_DENIED(403), CONSENT_REQUIRED(403), POLICY_REVIEW_FORBIDDEN(403),
    RESOURCE_NOT_FOUND(404), DAILY_PLAN_NOT_ASSIGNED(404),
    CONTENT_NOT_APPROVED(409), CONTENT_HASH_MISMATCH(409), POLICY_NOT_APPROVED(409),
    APPROVED_CONTENT_SHORTAGE(409), NARRATIVE_PATH_UNAVAILABLE(409), PLAN_ALREADY_ACTIVE(409),
    STALE_CHECKPOINT(409), STALE_WORLD_STATE(409), STALE_DRAFT(409), IDEMPOTENCY_CONFLICT(409),
    EVENT_SEQUENCE_GAP(409), PAYLOAD_TOO_LARGE(413), INVALID_SCHEMA(422), INVALID_BRANCH_PATH(422),
    NO_SPEECH(422), RATE_LIMITED(429), COST_BUDGET_EXCEEDED(429), STT_UNAVAILABLE(503),
    CARD_SERVICE_UNAVAILABLE(503), WORKER_UNAVAILABLE(503), UNSUPPORTED_SCHEMA_VERSION(422),
    EXECUTION_MODE_MISMATCH(409), REWARD_WINDOW_EXPIRED(409), CHECKPOINT_EXPIRED(409),
    EXECUTION_LEASE_EXPIRED(409), CONTENT_REVOKED(403), RESOURCE_DELETED(404),
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return status == 429 || status == 503;
    }

    public String message() {
        return switch (status) {
            case 401 -> "Authentication is required or no longer valid";
            case 403 -> "Access to this resource is not permitted";
            case 404 -> "The requested resource is unavailable";
            case 409 -> "The request conflicts with the current state";
            case 413 -> "The request payload is too large";
            case 422 -> "The request does not satisfy the API contract";
            case 429 -> "The request limit has been reached";
            case 503 -> "A required service is temporarily unavailable";
            default -> "The request could not be processed";
        };
    }
}
