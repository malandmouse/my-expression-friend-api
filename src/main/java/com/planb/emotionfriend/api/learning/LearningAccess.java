package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.children.*;
import com.planb.emotionfriend.api.common.security.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class LearningAccess {
    private final ChildAccessPolicy children;
    public LearningAccess(ChildAccessPolicy children) { this.children = children; }
    public void view(ActorPrincipal actor, UUID child) {
        children.check(actor, child.toString(), ChildAccessChecker.Capability.VIEW_SUMMARY);
    }
    public void manage(ActorPrincipal actor, UUID child) {
        children.check(actor, child.toString(), ChildAccessChecker.Capability.MANAGE);
        children.requireConsent(child, ChildContracts.ConsentScope.CORE_PROFILE_LEARNING);
    }
}
