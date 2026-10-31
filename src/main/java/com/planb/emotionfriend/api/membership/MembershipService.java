package com.planb.emotionfriend.api.membership;

import static com.planb.emotionfriend.api.auth.AuthContracts.*;
import static com.planb.emotionfriend.api.membership.MembershipContracts.*;

import com.planb.emotionfriend.api.auth.TokenSecrets;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

@Service
public class MembershipService {
    private final JdbcTemplate jdbc;
    private final MemberGuard guard;
    private final AuditTrail audit;
    private final IdempotencyService idempotency;
    private final JsonMapper mapper;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public MembershipService(
            JdbcTemplate jdbc,
            MemberGuard guard,
            AuditTrail audit,
            IdempotencyService idempotency,
            JsonMapper mapper,
            PasswordEncoder passwords,
            Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional
    public Reauthentication reauthenticate(ActorPrincipal actor, String password) {
        guard.lockDomain();
        guard.require(actor);
        String hash =
                jdbc.queryForObject(
                        "select password_hash from identity.member where id=?",
                        String.class,
                        UUID.fromString(actor.actorId()));
        if (!passwords.matches(password, hash)) throw new ApiException(ErrorCode.AUTH_REQUIRED);
        UUID ref = UUID.randomUUID();
        jdbc.update(
                "update identity.auth_session set reauthenticated_at=?, verification_ref=? where"
                        + " id=?",
                Timestamp.from(clock.instant()),
                ref,
                UUID.fromString(actor.sessionId()));
        return new Reauthentication(ref, clock.instant().plusSeconds(300));
    }

    @Transactional
    public void requestEmail(ActorPrincipal actor) {
        guard.lockDomain();
        var account = guard.session(actor);
        if (account.emailVerified()) return;
        UUID id = UUID.randomUUID();
        String token = TokenSecrets.refresh();
        jdbc.update(
                "update identity.email_verification set consumed_at=? where member_id=? and"
                        + " consumed_at is null",
                Timestamp.from(clock.instant()),
                account.id());
        jdbc.update(
                "insert into identity.email_verification"
                        + " (id,member_id,token_hash,expires_at,created_at) values (?,?,?,?,?)",
                id,
                account.id(),
                TokenSecrets.hash(token),
                Timestamp.from(clock.instant().plusSeconds(900)),
                Timestamp.from(clock.instant()));
        audit.notify(
                account.id(), "EMAIL_VERIFICATION", Map.of("verificationId", id, "token", token));
    }

    @Transactional
    public void verifyEmail(ActorPrincipal actor, VerifyEmailRequest request) {
        guard.lockDomain();
        var account = guard.session(actor);
        var rows =
                jdbc.query(
                        "select token_hash, expires_at, consumed_at from"
                            + " identity.email_verification where id=? and member_id=? for update",
                        (rs, row) ->
                                rs.getTimestamp("consumed_at") == null
                                        && rs.getTimestamp("expires_at")
                                                .toInstant()
                                                .isAfter(clock.instant())
                                        && java.security.MessageDigest.isEqual(
                                                rs.getString("token_hash")
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .US_ASCII),
                                                TokenSecrets.hash(request.token())
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .US_ASCII)),
                        request.verificationId(),
                        account.id());
        if (rows.isEmpty() || !rows.getFirst())
            throw new ApiException(ErrorCode.VERIFICATION_INVALID);
        jdbc.update(
                "update identity.email_verification set consumed_at=? where id=?",
                Timestamp.from(clock.instant()),
                request.verificationId());
        jdbc.update(
                "update identity.member set"
                    + " email_verified_at=coalesce(email_verified_at,?),updated_at=? where id=?",
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()),
                account.id());
        audit.record(
                account.id(),
                "EMAIL_VERIFIED",
                account.id(),
                null,
                Map.of("verified", false),
                Map.of("verified", true));
    }

    @Transactional
    public Map<String, Object> members(ActorPrincipal actor, Status status, int limit, UUID after) {
        guard.lockDomain();
        guard.require(actor, Role.ADMIN);
        if (limit < 1 || limit > 100) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        var rows =
                jdbc.query(
                        "select id from identity.member where (?::varchar is null or status=?) and"
                                + " (?::uuid is null or id>?::uuid) order by id limit ?",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        status == null ? null : status.name(),
                        status == null ? null : status.name(),
                        after,
                        after,
                        limit + 1);
        var response = new LinkedHashMap<String, Object>();
        response.put("items", rows.stream().limit(limit).map(this::view).toList());
        response.put("nextCursor", rows.size() > limit ? rows.get(limit - 1) : null);
        return response;
    }

    public StoredResponse review(
            ActorPrincipal actor, UUID target, String action, ReviewRequest body, String key) {
        String path = "/api/v1/admin/members/" + target + "/" + action;
        return idempotency.execute(
                actor,
                "POST",
                path,
                key,
                body,
                () -> {
                    guard.lockDomain();
                    guard.recent(actor, Role.ADMIN);
                },
                () -> {
                    MemberView before = view(target);
                    if (before.role() == Role.ADMIN || target.toString().equals(actor.actorId()))
                        throw new ApiException(ErrorCode.ROLE_FORBIDDEN);
                    Status next =
                            switch (action) {
                                case "approve" -> Status.ACTIVE;
                                case "reject" -> Status.REJECTED;
                                case "suspend" -> Status.SUSPENDED;
                                default -> throw new ApiException(ErrorCode.INVALID_SCHEMA);
                            };
                    if ((next == Status.ACTIVE
                                    && (before.status() == Status.ACTIVE
                                            || !before.emailVerified()))
                            || (next == Status.REJECTED && before.status() != Status.PENDING)
                            || (next == Status.SUSPENDED && before.status() != Status.ACTIVE))
                        throw new ApiException(ErrorCode.MEMBER_STATE_CONFLICT);
                    jdbc.update(
                            "update identity.member set status=?,role=?,updated_at=? where id=?",
                            next.name(),
                            next == Status.ACTIVE
                                    ? before.requestedRole().name()
                                    : before.role() == null ? null : before.role().name(),
                            Timestamp.from(clock.instant()),
                            target);
                    invalidate(target);
                    MemberView after = view(target);
                    audit.record(
                            UUID.fromString(actor.actorId()),
                            "MEMBER_" + action.toUpperCase(Locale.ROOT),
                            target,
                            body.reason(),
                            state(before),
                            Map.of(
                                    "state",
                                    state(after),
                                    "reviewReference",
                                    body.reviewReference()));
                    audit.notify(target, "MEMBER_REVIEWED", Map.of("status", next.name()));
                    return StoredResponse.json(mapper, 200, after);
                });
    }

    public StoredResponse changeRole(
            ActorPrincipal actor, UUID target, RoleChangeRequest body, String key) {
        return idempotency.execute(
                actor,
                "POST",
                "/api/v1/admin/members/" + target + "/role",
                key,
                body,
                () -> {
                    guard.lockDomain();
                    guard.recent(actor, Role.ADMIN);
                },
                () -> {
                    MemberView before = view(target);
                    if (before.role() == Role.ADMIN || target.toString().equals(actor.actorId()))
                        throw new ApiException(ErrorCode.ROLE_FORBIDDEN);
                    if (before.status() != Status.ACTIVE
                            || before.role().name().equals(body.role().name()))
                        throw new ApiException(ErrorCode.MEMBER_STATE_CONFLICT);
                    if (Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "select exists(select 1 from care.child where guardian_id=? and"
                                            + " deleted_at is null)",
                                    Boolean.class,
                                    target)))
                        throw new ApiException(ErrorCode.MEMBER_STATE_CONFLICT);
                    jdbc.update(
                            "update identity.member set role=?,requested_role=?,updated_at=? where"
                                    + " id=?",
                            body.role().name(),
                            body.role().name(),
                            Timestamp.from(clock.instant()),
                            target);
                    invalidate(target);
                    MemberView after = view(target);
                    audit.record(
                            UUID.fromString(actor.actorId()),
                            "MEMBER_ROLE_CHANGED",
                            target,
                            body.reason(),
                            state(before),
                            Map.of(
                                    "state",
                                    state(after),
                                    "reviewReference",
                                    body.reviewReference()));
                    audit.notify(target, "MEMBER_ROLE_CHANGED", Map.of("role", body.role().name()));
                    return StoredResponse.json(mapper, 200, after);
                });
    }

    private void invalidate(UUID member) {
        jdbc.update(
                "update identity.auth_session set revoked_at=coalesce(revoked_at,?) where"
                        + " member_id=?",
                Timestamp.from(clock.instant()),
                member);
        jdbc.update(
                "update care.therapist_invitation set state='CANCELLED',resolved_at=? where"
                        + " (guardian_id=? or therapist_id=?) and state='PENDING'",
                Timestamp.from(clock.instant()),
                member,
                member);
        jdbc.update(
                "update care.child_therapist set state='REVOKED',revoked_at=? where therapist_id=?"
                        + " or child_id in (select id from care.child where guardian_id=?)",
                Timestamp.from(clock.instant()),
                member,
                member);
    }

    private Map<String, Object> state(MemberView value) {
        var map = new LinkedHashMap<String, Object>();
        map.put("role", value.role());
        map.put("status", value.status());
        return map;
    }

    private MemberView view(UUID id) {
        var rows =
                jdbc.query(
                        "select * from identity.member where id=?",
                        (rs, row) ->
                                new MemberView(
                                        id,
                                        rs.getString("email"),
                                        rs.getString("display_name"),
                                        rs.getString("requested_role") == null
                                                ? null
                                                : RequestedRole.valueOf(
                                                        rs.getString("requested_role")),
                                        rs.getString("role") == null
                                                ? null
                                                : Role.valueOf(rs.getString("role")),
                                        Status.valueOf(rs.getString("status")),
                                        rs.getTimestamp("email_verified_at") != null,
                                        rs.getTimestamp("created_at").toInstant()),
                        id);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return rows.getFirst();
    }
}
