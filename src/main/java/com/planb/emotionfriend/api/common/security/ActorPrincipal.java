package com.planb.emotionfriend.api.common.security;

import java.security.Principal;

/** Verified identity only. Secrets and mutable child permissions never belong here. */
public record ActorPrincipal(String actorId, Kind kind, String childId, String deviceId, String planId,
                             String sessionId, boolean accountRestricted) implements Principal {
    public enum Kind { ADULT, CHILD }

    public ActorPrincipal(String actorId, Kind kind, String childId, String deviceId, String planId) {
        this(actorId, kind, childId, deviceId, planId, null, false);
    }

    public ActorPrincipal {
        requireId(actorId);
        if (kind == null) throw new IllegalArgumentException("Credential kind is required");
        if (kind == Kind.CHILD) {
            requireId(childId);
            requireId(deviceId);
        } else if (childId != null || deviceId != null || planId != null) {
            throw new IllegalArgumentException("Adult credentials cannot carry child runtime scope");
        }
        if (planId != null) requireId(planId);
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 256) throw new IllegalArgumentException("Invalid identity scope");
    }

    @Override public String getName() { return actorId; }
    @Override public String toString() { return "ActorPrincipal[redacted]"; }
}
