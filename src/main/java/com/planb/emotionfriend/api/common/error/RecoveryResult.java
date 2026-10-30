package com.planb.emotionfriend.api.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecoveryResult(String status, boolean checkpointRestorable, Boolean localDraftRestorable,
                             String nextAction, String viewPattern, boolean regularAwardMutation) {
    static RecoveryResult forError(ErrorCode code) {
        return switch (code) {
            case CHECKPOINT_EXPIRED -> new RecoveryResult(code.name(), false, null, "RETURN_HOME", "U13", false);
            case CONTENT_REVOKED, RESOURCE_DELETED ->
                    new RecoveryResult(code.name(), false, null, "RETURN_HOME", null, false);
            case EXECUTION_LEASE_EXPIRED ->
                    new RecoveryResult(code.name(), false, null, "RECHECK_ONLINE", null, false);
            case REWARD_WINDOW_EXPIRED ->
                    new RecoveryResult(code.name(), false, null, "START_REVIEW", null, false);
            default -> null;
        };
    }
}
