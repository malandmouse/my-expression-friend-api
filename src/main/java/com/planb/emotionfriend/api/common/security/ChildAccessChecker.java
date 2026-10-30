package com.planb.emotionfriend.api.common.security;

/** Implement using current DB state, including resource ownership, consent, deletion and revocation. */
@FunctionalInterface
public interface ChildAccessChecker {
    void check(ActorPrincipal actor, String childId, Capability capability);

    enum Capability { MANAGE, VIEW_PROFILE, EDIT_PROFILE, CONTENT_REVIEW, VIEW_SUMMARY }
}
