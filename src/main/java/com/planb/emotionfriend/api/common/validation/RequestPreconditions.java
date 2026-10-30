package com.planb.emotionfriend.api.common.validation;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;

public final class RequestPreconditions {
    private RequestPreconditions() {}

    public static String idempotencyKey(String key) {
        if (key == null || key.isBlank() || key.codePointCount(0, key.length()) > 256
                || key.codePoints().anyMatch(Character::isISOControl)) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        return key;
    }

    public static long checkpointRevision(String ifMatch, long bodyRevision) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9]{1,18}\"")) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        long revision = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        if (bodyRevision != revision) throw new ApiException(ErrorCode.STALE_CHECKPOINT);
        return revision;
    }
}
