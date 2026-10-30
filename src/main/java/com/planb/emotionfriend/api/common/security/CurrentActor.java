package com.planb.emotionfriend.api.common.security;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentActor {
    public ActorPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ActorPrincipal actor)) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        return actor;
    }

    public ActorPrincipal require(ActorPrincipal.Kind kind) {
        ActorPrincipal actor = require();
        if (actor.kind() != kind) throw new ApiException(ErrorCode.CHILD_ACCESS_DENIED);
        return actor;
    }
}
