package com.planb.emotionfriend.api.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetails(Long expectedCheckpointRevision, Long expectedWorldRevision, Long expectedClientSeq,
                           String nextAction, RecoveryResult recovery) {
    public static ErrorDetails forError(ErrorCode code) {
        RecoveryResult recovery = RecoveryResult.forError(code);
        return new ErrorDetails(null, null, null, recovery == null ? null : recovery.nextAction(), recovery);
    }

    public static ErrorDetails checkpoint(long revision) {
        if (revision < 0) throw new IllegalArgumentException("Revision must be non-negative");
        return new ErrorDetails(revision, null, null, null, null);
    }
}
