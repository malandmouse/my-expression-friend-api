package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import java.util.UUID;

/** Deployment-owned, resource-scoped capability adapter. No role implies either capability. */
public interface ContentAuthority {
    enum Capability { CONTENT_REVIEW, CONTENT_PUBLISH }
    void require(ActorPrincipal actor, UUID child, Capability capability);
}
