package com.planb.emotionfriend.api.common.security;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class ResourceAccess {
    private final ChildAccessChecker checker;

    public ResourceAccess(ChildAccessChecker checker) { this.checker = checker; }

    public void require(ActorPrincipal actor, String childId, ChildAccessChecker.Capability capability) {
        if (actor == null) throw new ApiException(ErrorCode.AUTH_REQUIRED);
        if (childId == null || childId.isBlank() || capability == null) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        if (actor.kind() == ActorPrincipal.Kind.CHILD && !childId.equals(actor.childId())) {
            throw new ApiException(ErrorCode.CHILD_ACCESS_DENIED);
        }
        // No permission cache: callers must invoke this inside every write/replay transaction.
        checker.check(actor, childId, capability);
    }
}
