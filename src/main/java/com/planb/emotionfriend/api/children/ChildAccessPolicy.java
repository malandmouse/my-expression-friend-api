package com.planb.emotionfriend.api.children;

import com.planb.emotionfriend.api.auth.AuthContracts.Role;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.membership.MemberGuard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
public class ChildAccessPolicy implements ChildAccessChecker {
    private final JdbcTemplate jdbc;
    private final MemberGuard guard;

    public ChildAccessPolicy(JdbcTemplate jdbc, MemberGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    @Override
    @Transactional
    public void check(ActorPrincipal actor, String childId, Capability capability) {
        guard.lockDomain();
        var account = guard.require(actor, Role.GUARDIAN, Role.THERAPIST);
        UUID child;
        try {
            child = UUID.fromString(childId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        var owners =
                jdbc.query(
                        """
select c.guardian_id from care.child c join identity.member g on g.id=c.guardian_id
where c.id=? and c.deleted_at is null and g.status='ACTIVE' and g.role='GUARDIAN' and g.email_verified_at is not null
""",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        child);
        if (owners.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        boolean owner = account.role() == Role.GUARDIAN && owners.getFirst().equals(account.id());
        boolean therapist =
                account.role() == Role.THERAPIST
                        && Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "select exists(select 1 from care.child_therapist where"
                                            + " child_id=? and therapist_id=? and state='ACTIVE')",
                                        Boolean.class,
                                        child,
                                        account.id()));
        if (!owner && !therapist) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        if (capability == Capability.CONTENT_REVIEW
                || (!owner
                        && capability != Capability.VIEW_PROFILE
                        && capability != Capability.VIEW_SUMMARY))
            throw new ApiException(ErrorCode.CHILD_ACCESS_DENIED);
        if (therapist || capability == Capability.VIEW_SUMMARY)
            requireConsent(child, ChildContracts.ConsentScope.CORE_PROFILE_LEARNING);
    }

    public void requireConsent(UUID child, ChildContracts.ConsentScope scope) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from care.consent where child_id=? and scope=? and"
                                + " granted)",
                        Boolean.class,
                        child,
                        scope.name()))) throw new ApiException(ErrorCode.CONSENT_REQUIRED);
    }

    public static List<Capability> capabilities(Role role) {
        return role == Role.GUARDIAN
                ? List.of(
                        Capability.MANAGE,
                        Capability.VIEW_PROFILE,
                        Capability.EDIT_PROFILE,
                        Capability.VIEW_SUMMARY)
                : List.of(Capability.VIEW_PROFILE, Capability.VIEW_SUMMARY);
    }
}
