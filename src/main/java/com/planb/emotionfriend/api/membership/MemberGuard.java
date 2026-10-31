package com.planb.emotionfriend.api.membership;

import com.planb.emotionfriend.api.auth.AuthContracts.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Recheck mutable account/session authorization inside each business transaction, including replay.
 */
@Component
public class MemberGuard {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MemberGuard(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void lockDomain() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Authorization requires a transaction");
        // Initial low-volume implementation serializes relationship changes across instances.
        // Auth login/refresh still lock member then session; this lock must precede business row
        // locks.
        jdbc.query("select pg_advisory_xact_lock(1771, 31)", rs -> {});
    }

    public Account require(ActorPrincipal actor, Role... roles) {
        Account account = session(actor);
        if (account.status() != Status.ACTIVE || !account.emailVerified() || account.role() == null)
            throw new ApiException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        if (roles.length > 0 && java.util.Arrays.stream(roles).noneMatch(r -> r == account.role()))
            throw new ApiException(ErrorCode.ROLE_FORBIDDEN);
        return account;
    }

    public Account session(ActorPrincipal actor) {
        if (actor == null || actor.kind() != ActorPrincipal.Kind.ADULT || actor.sessionId() == null)
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        jdbc.query(
                "select id from identity.member where id=? for share",
                rs -> {},
                UUID.fromString(actor.actorId()));
        var rows =
                jdbc.query(
                        """
select m.id, m.role, m.status, m.email_verified_at, s.reauthenticated_at, s.verification_ref
from identity.member m join identity.auth_session s on s.member_id=m.id
where m.id=? and s.id=? and s.revoked_at is null and s.expires_at>? for share of s
""",
                        (rs, row) ->
                                new Account(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("role") == null
                                                ? null
                                                : Role.valueOf(rs.getString("role")),
                                        Status.valueOf(rs.getString("status")),
                                        rs.getTimestamp("email_verified_at") != null,
                                        rs.getTimestamp("reauthenticated_at") == null
                                                ? null
                                                : rs.getTimestamp("reauthenticated_at").toInstant(),
                                        rs.getObject("verification_ref", UUID.class)),
                        UUID.fromString(actor.actorId()),
                        UUID.fromString(actor.sessionId()),
                        Timestamp.from(clock.instant()));
        if (rows.isEmpty() || rows.getFirst().status() == Status.SUSPENDED)
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        return rows.getFirst();
    }

    public Account recent(ActorPrincipal actor, Role... roles) {
        Account account = require(actor, roles);
        if (account.reauthenticatedAt() == null
                || !account.reauthenticatedAt().isAfter(clock.instant().minusSeconds(300)))
            throw new ApiException(ErrorCode.REAUTH_REQUIRED);
        return account;
    }

    public record Account(
            UUID id,
            Role role,
            Status status,
            boolean emailVerified,
            Instant reauthenticatedAt,
            UUID verificationRef) {}
}
