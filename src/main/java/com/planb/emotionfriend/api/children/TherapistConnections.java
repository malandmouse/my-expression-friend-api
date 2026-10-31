package com.planb.emotionfriend.api.children;

import static com.planb.emotionfriend.api.common.security.ChildAccessChecker.Capability.*;

import com.planb.emotionfriend.api.auth.AuthContracts.Role;
import com.planb.emotionfriend.api.auth.AuthRateLimiter;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.membership.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class TherapistConnections {
    private final JdbcTemplate jdbc;
    private final MemberGuard guard;
    private final ChildAccessPolicy access;
    private final IdempotencyService idempotency;
    private final AuditTrail audit;
    private final JsonMapper mapper;
    private final Clock clock;
    private final AuthRateLimiter limits;

    public TherapistConnections(
            JdbcTemplate jdbc,
            MemberGuard guard,
            ChildAccessPolicy access,
            IdempotencyService idempotency,
            AuditTrail audit,
            JsonMapper mapper,
            Clock clock,
            AuthRateLimiter limits) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.limits = limits;
    }

    public StoredResponse invite(
            ActorPrincipal actor,
            UUID child,
            ChildContracts.InvitationRequest body,
            String key,
            String ip) {
        // A short ownership preflight prevents spending another child's rate budget. Rate-limit
        // transactions must finish before execution acquires a connection/authorization lock;
        // nesting REQUIRES_NEW here can exhaust the pool during simultaneous requests.
        access.check(actor, child.toString(), MANAGE);
        limits.consume("invite-member", actor.actorId(), 30, Duration.ofDays(1));
        limits.consume("invite-child", child.toString(), 20, Duration.ofDays(1));
        limits.consume(
                "invite-recipient",
                body.therapistEmail().strip().toLowerCase(Locale.ROOT),
                20,
                Duration.ofDays(1));
        limits.consume("invite-ip", ip, 100, Duration.ofDays(1));
        return idempotency.execute(
                actor,
                "POST",
                "/api/v1/children/" + child + "/therapist-invitations",
                key,
                body,
                () -> {
                    guard.lockDomain();
                    access.check(actor, child.toString(), MANAGE);
                    guard.recent(actor, Role.GUARDIAN);
                    access.requireConsent(child, ChildContracts.ConsentScope.CORE_PROFILE_LEARNING);
                },
                () -> {
                    String email = body.therapistEmail().strip().toLowerCase(Locale.ROOT);
                    jdbc.update(
                            "update care.therapist_invitation set state='EXPIRED',resolved_at=?"
                                    + " where child_id=? and state='PENDING' and expires_at<=?",
                            Timestamp.from(clock.instant()),
                            child,
                            Timestamp.from(clock.instant()));
                    boolean pending =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "select exists(select 1 from care.therapist_invitation"
                                                    + " where child_id=? and target_email=? and"
                                                    + " state='PENDING')",
                                            Boolean.class,
                                            child,
                                            email));
                    if (!pending) {
                        var recipients =
                                jdbc.query(
                                        "select id from identity.member where email=? and"
                                                + " status='ACTIVE' and role='THERAPIST' and"
                                                + " email_verified_at is not null",
                                        (rs, row) -> rs.getObject(1, UUID.class),
                                        email);
                        UUID recipient = recipients.isEmpty() ? null : recipients.getFirst();
                        UUID id = UUID.randomUUID();
                        jdbc.update(
                                "insert into"
                                    + " care.therapist_invitation(id,child_id,guardian_id,therapist_id,target_email,state,created_at,expires_at)"
                                    + " values(?,?,?,?,?,'PENDING',?,?)",
                                id,
                                child,
                                UUID.fromString(actor.actorId()),
                                recipient,
                                email,
                                Timestamp.from(clock.instant()),
                                Timestamp.from(clock.instant().plusSeconds(86400)));
                        if (recipient != null)
                            audit.notify(
                                    recipient, "THERAPIST_INVITATION", Map.of("invitationId", id));
                        audit.record(
                                UUID.fromString(actor.actorId()),
                                "THERAPIST_INVITED",
                                child,
                                null,
                                Map.of(),
                                Map.of("invitationId", id));
                    }
                    return StoredResponse.json(mapper, 202, MembershipContracts.Accepted.generic());
                });
    }

    @Transactional
    public Map<String, Object> guardianInvitations(ActorPrincipal actor, UUID child) {
        guard.lockDomain();
        access.check(actor, child.toString(), MANAGE);
        return Map.of(
                "items",
                jdbc.query(
                        "select id,target_email,state,created_at,expires_at from"
                            + " care.therapist_invitation where child_id=? order by created_at desc"
                            + " limit 100",
                        (rs, row) ->
                                Map.<String, Object>of(
                                        "invitationId",
                                        rs.getObject(1, UUID.class),
                                        "therapistEmail",
                                        rs.getString(2),
                                        "state",
                                        effectiveState(
                                                rs.getString(3), rs.getTimestamp(5).toInstant()),
                                        "createdAt",
                                        rs.getTimestamp(4).toInstant(),
                                        "expiresAt",
                                        rs.getTimestamp(5).toInstant()),
                        child));
    }

    @Transactional
    public Map<String, Object> inbox(ActorPrincipal actor) {
        guard.lockDomain();
        var account = guard.require(actor, Role.THERAPIST);
        return Map.of(
                "items",
                jdbc.query(
                        """
select i.id,g.display_name,g.email,i.created_at,i.expires_at from care.therapist_invitation i
join care.child c on c.id=i.child_id join identity.member g on g.id=i.guardian_id
where i.therapist_id=? and i.state='PENDING' and i.expires_at>? and c.deleted_at is null
and g.status='ACTIVE' and g.role='GUARDIAN' order by i.created_at desc limit 100
""",
                        (rs, row) ->
                                Map.<String, Object>of(
                                        "invitationId",
                                        rs.getObject(1, UUID.class),
                                        "guardianDisplayName",
                                        rs.getString(2),
                                        "guardianContact",
                                        mask(rs.getString(3)),
                                        "createdAt",
                                        rs.getTimestamp(4).toInstant(),
                                        "expiresAt",
                                        rs.getTimestamp(5).toInstant(),
                                        "capabilities",
                                        ChildAccessPolicy.capabilities(Role.THERAPIST)),
                        account.id(),
                        Timestamp.from(clock.instant())));
    }

    public StoredResponse respond(
            ActorPrincipal actor, UUID id, boolean accept, Object body, String key) {
        return idempotency.execute(
                actor,
                "POST",
                "/api/v1/therapist-invitations/" + id + (accept ? "/accept" : "/decline"),
                key,
                body,
                () -> {
                    guard.lockDomain();
                    if (accept) guard.recent(actor, Role.THERAPIST);
                    else guard.require(actor, Role.THERAPIST);
                    Invite invitation = recipientInvitation(actor, id);
                    if (accept && invitation.state().equals("ACCEPTED"))
                        access.check(actor, invitation.child().toString(), VIEW_PROFILE);
                },
                () -> {
                    Invite invitation = recipientInvitation(actor, id);
                    if (!invitation.state().equals("PENDING")
                            || !invitation.expiresAt().isAfter(clock.instant()))
                        throw new ApiException(ErrorCode.INVITATION_STATE_CONFLICT);
                    if (!Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    """
select exists(select 1 from care.child c join identity.member g on g.id=c.guardian_id
join identity.member t on t.id=? where c.id=? and c.guardian_id=? and c.deleted_at is null
and g.status='ACTIVE' and g.role='GUARDIAN' and g.email_verified_at is not null
and t.email=? and t.role='THERAPIST' and t.status='ACTIVE' and t.email_verified_at is not null)
""",
                                    Boolean.class,
                                    UUID.fromString(actor.actorId()),
                                    invitation.child(),
                                    invitation.guardian(),
                                    invitation.email())))
                        throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
                    if (accept) {
                        access.requireConsent(
                                invitation.child(),
                                ChildContracts.ConsentScope.CORE_PROFILE_LEARNING);
                        jdbc.update(
                                """
insert into care.child_therapist(child_id,therapist_id,invitation_id,state,accepted_at) values(?,?,?,'ACTIVE',?)
on conflict(child_id,therapist_id) do update set invitation_id=excluded.invitation_id,state='ACTIVE',accepted_at=excluded.accepted_at,revoked_at=null
""",
                                invitation.child(),
                                UUID.fromString(actor.actorId()),
                                id,
                                Timestamp.from(clock.instant()));
                    }
                    jdbc.update(
                            "update care.therapist_invitation set state=?,resolved_at=? where id=?",
                            accept ? "ACCEPTED" : "DECLINED",
                            Timestamp.from(clock.instant()),
                            id);
                    audit.record(
                            UUID.fromString(actor.actorId()),
                            accept ? "THERAPIST_ACCEPTED" : "THERAPIST_DECLINED",
                            invitation.child(),
                            null,
                            Map.of("invitationId", id),
                            Map.of(
                                    "state",
                                    accept ? "ACTIVE" : "DECLINED",
                                    "relationshipConfirmed",
                                    accept));
                    audit.notify(
                            invitation.guardian(),
                            accept ? "THERAPIST_CONNECTED" : "THERAPIST_DECLINED",
                            Map.of("invitationId", id));
                    return StoredResponse.noContent();
                });
    }

    public StoredResponse cancel(ActorPrincipal actor, UUID child, UUID id, String key) {
        return idempotency.execute(
                actor,
                "DELETE",
                "/api/v1/children/" + child + "/therapist-invitations/" + id,
                key,
                Map.of(),
                () -> {
                    guard.lockDomain();
                    access.check(actor, child.toString(), MANAGE);
                },
                () -> {
                    int count =
                            jdbc.update(
                                    "update care.therapist_invitation set"
                                            + " state='CANCELLED',resolved_at=? where id=? and"
                                            + " child_id=? and state='PENDING'",
                                    Timestamp.from(clock.instant()),
                                    id,
                                    child);
                    if (count != 1) throw new ApiException(ErrorCode.INVITATION_STATE_CONFLICT);
                    audit.record(
                            UUID.fromString(actor.actorId()),
                            "INVITATION_CANCELLED",
                            child,
                            null,
                            Map.of("invitationId", id),
                            Map.of("state", "CANCELLED"));
                    return StoredResponse.noContent();
                });
    }

    @Transactional
    public Map<String, Object> therapists(ActorPrincipal actor, UUID child) {
        guard.lockDomain();
        access.check(actor, child.toString(), MANAGE);
        return Map.of(
                "items",
                jdbc.query(
                        "select m.id,m.display_name,t.accepted_at from care.child_therapist t join"
                            + " identity.member m on m.id=t.therapist_id where t.child_id=? and"
                            + " t.state='ACTIVE' and m.status='ACTIVE' and m.role='THERAPIST' order"
                            + " by m.id limit 100",
                        (rs, row) ->
                                Map.<String, Object>of(
                                        "therapistId",
                                        rs.getObject(1, UUID.class),
                                        "displayName",
                                        rs.getString(2),
                                        "acceptedAt",
                                        rs.getTimestamp(3).toInstant(),
                                        "capabilities",
                                        ChildAccessPolicy.capabilities(Role.THERAPIST)),
                        child));
    }

    public StoredResponse disconnect(ActorPrincipal actor, UUID child, UUID therapist, String key) {
        return idempotency.execute(
                actor,
                "DELETE",
                "/api/v1/children/" + child + "/therapists/" + therapist,
                key,
                Map.of(),
                () -> {
                    guard.lockDomain();
                    var account = guard.require(actor, Role.GUARDIAN, Role.THERAPIST);
                    if (account.role() == Role.GUARDIAN)
                        access.check(actor, child.toString(), MANAGE);
                    else if (!account.id().equals(therapist)
                            || !Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "select exists(select 1 from care.child_therapist where"
                                                    + " child_id=? and therapist_id=?)",
                                            Boolean.class,
                                            child,
                                            therapist)))
                        throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
                },
                () -> {
                    int count =
                            jdbc.update(
                                    "update care.child_therapist set state='REVOKED',revoked_at=?"
                                            + " where child_id=? and therapist_id=?",
                                    Timestamp.from(clock.instant()),
                                    child,
                                    therapist);
                    if (count != 1) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
                    jdbc.update(
                            "update care.therapist_invitation set state='CANCELLED',resolved_at=?"
                                    + " where child_id=? and therapist_id=? and state='PENDING'",
                            Timestamp.from(clock.instant()),
                            child,
                            therapist);
                    audit.record(
                            UUID.fromString(actor.actorId()),
                            "THERAPIST_DISCONNECTED",
                            child,
                            null,
                            Map.of("therapistId", therapist),
                            Map.of("state", "REVOKED"));
                    return StoredResponse.noContent();
                });
    }

    private Invite recipientInvitation(ActorPrincipal actor, UUID id) {
        var rows =
                jdbc.query(
                        "select child_id,guardian_id,target_email,state,expires_at from"
                                + " care.therapist_invitation where id=? and therapist_id=?",
                        (rs, row) ->
                                new Invite(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getTimestamp(5).toInstant()),
                        id,
                        UUID.fromString(actor.actorId()));
        if (rows.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return rows.getFirst();
    }

    private String effectiveState(String state, Instant expiry) {
        return state.equals("PENDING") && !expiry.isAfter(clock.instant()) ? "EXPIRED" : state;
    }

    private String mask(String email) {
        int at = email.indexOf('@');
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private record Invite(
            UUID child, UUID guardian, String email, String state, Instant expiresAt) {
        @Override
        public String toString() {
            return "Invite[redacted]";
        }
    }
}
