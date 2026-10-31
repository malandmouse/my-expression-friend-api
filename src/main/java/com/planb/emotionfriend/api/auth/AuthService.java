package com.planb.emotionfriend.api.auth;

import static com.planb.emotionfriend.api.auth.AuthContracts.*;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import com.planb.emotionfriend.api.common.security.AccessCredentialVerifier;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService implements AccessCredentialVerifier {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AdultJwt jwt;
    private final Clock clock;
    private final AuthConfiguration.Properties properties;
    private final TransactionTemplate transaction;
    private final String dummyPasswordHash;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, AdultJwt jwt, Clock clock,
                       AuthConfiguration.Properties properties, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.jwt = jwt;
        this.clock = clock;
        this.properties = properties;
        transaction = new TransactionTemplate(manager);
        // Unknown emails still pay the password verification cost; never log credentials or hashes.
        dummyPasswordHash = passwords.encode(TokenSecrets.refresh());
    }

    public MemberView signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        String name = request.displayName().strip();
        if (name.isEmpty() || name.codePoints().anyMatch(Character::isISOControl)
                || request.password().codePoints().anyMatch(c -> c == 0)) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        String hash = passwords.encode(request.password());
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        boolean created = Boolean.TRUE.equals(transaction.execute(status -> jdbc.update("""
                insert into identity.member (id, email, display_name, password_hash, requested_role, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'PENDING', ?, ?) on conflict (email) do nothing
                """, id, email, name, hash, request.requestedRole().name(), Timestamp.from(now), Timestamp.from(now)) == 1));
        if (!created) throw new ApiException(ErrorCode.ACCOUNT_ALREADY_EXISTS);
        return new MemberView(id, email, name, request.requestedRole(), null, Status.PENDING, false, now);
    }

    public Credentials login(LoginRequest request) {
        List<Member> candidates = jdbc.query("select * from identity.member where email=?", this::member, normalizeEmail(request.loginId()));
        Member candidate = candidates.isEmpty() ? null : candidates.getFirst();
        boolean matches = passwords.matches(request.password(), candidate == null ? dummyPasswordHash : candidate.passwordHash());
        if (!matches || candidate == null) throw new ApiException(ErrorCode.AUTH_REQUIRED);
        Result result = transaction.execute(status -> {
            Member current = byId(candidate.id(), true);
            if (!current.passwordHash().equals(candidate.passwordHash()) || current.status() == Status.SUSPENDED) {
                return Result.error(ErrorCode.AUTH_REQUIRED);
            }
            UUID sessionId = UUID.randomUUID();
            Instant now = clock.instant();
            Instant expiry = now.plus(properties.sessionTtl());
            jdbc.update("insert into identity.auth_session (id, member_id, created_at, expires_at) values (?, ?, ?, ?)",
                    sessionId, current.id(), Timestamp.from(now), Timestamp.from(expiry));
            return Result.success(issue(current.id(), sessionId, expiry));
        });
        return unwrap(result);
    }

    public Credentials refresh(String token) {
        if (!isRefreshToken(token)) throw new ApiException(ErrorCode.AUTH_REQUIRED);
        String hash = TokenSecrets.hash(token);
        // Expected authentication failures return from the transaction so revocation is committed.
        Result result = transaction.execute(status -> {
            Refresh saved = findRefresh(hash);
            if (saved == null) return Result.error(ErrorCode.AUTH_REQUIRED);
            Session session = lockSession(saved.sessionId());
            if (session == null || session.revokedAt() != null) return Result.error(ErrorCode.AUTH_REQUIRED);
            if (session.member().status() == Status.SUSPENDED) {
                revoke(session.id());
                return Result.error(ErrorCode.AUTH_REQUIRED);
            }
            if (!session.expiresAt().isAfter(clock.instant())) return Result.error(ErrorCode.TOKEN_EXPIRED);
            // Re-read after locking the session: another request may have consumed this token while we waited.
            saved = findRefresh(hash);
            if (saved.consumedAt() != null) {
                revoke(session.id());
                return Result.error(ErrorCode.AUTH_REQUIRED);
            }
            jdbc.update("update identity.refresh_token set consumed_at=? where token_hash=?", Timestamp.from(clock.instant()), hash);
            return Result.success(issue(session.member().id(), session.id(), session.expiresAt()));
        });
        return unwrap(result);
    }

    public void logout(ActorPrincipal actor, String token) {
        if (actor.kind() != ActorPrincipal.Kind.ADULT || actor.sessionId() == null || !isRefreshToken(token)) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        ErrorCode error = transaction.execute(status -> {
            Refresh saved = findRefresh(TokenSecrets.hash(token));
            if (saved == null || !saved.sessionId().toString().equals(actor.sessionId())) return ErrorCode.AUTH_REQUIRED;
            Session session = lockSession(saved.sessionId());
            if (session == null || !session.member().id().toString().equals(actor.actorId())) return ErrorCode.AUTH_REQUIRED;
            revoke(session.id());
            return null;
        });
        if (error != null) throw new ApiException(error);
    }

    public MemberView me(ActorPrincipal actor) {
        if (actor.kind() != ActorPrincipal.Kind.ADULT) throw new ApiException(ErrorCode.AUTH_REQUIRED);
        return byId(UUID.fromString(actor.actorId()), false).view();
    }

    @Override
    public ActorPrincipal verify(String credential) {
        AdultJwt.Verified verified = jwt.verify(credential);
        return transaction.execute(status -> {
            var members = jdbc.query("select * from identity.member where id=? for share", this::member, verified.memberId());
            if (members.isEmpty()) throw new ApiException(ErrorCode.AUTH_REQUIRED);
            Member member = members.getFirst();
            var rows = jdbc.query("""
                    select id as session_id, expires_at, revoked_at from identity.auth_session
                    where id=? and member_id=? for share
                    """, (rs, row) -> new Session(rs.getObject("session_id", UUID.class), member,
                    instant(rs, "expires_at"), instant(rs, "revoked_at")), verified.sessionId(), verified.memberId());
            if (rows.isEmpty()) throw new ApiException(ErrorCode.AUTH_REQUIRED);
            Session saved = rows.getFirst();
            if (saved.revokedAt() != null || saved.member().status() == Status.SUSPENDED) throw new ApiException(ErrorCode.AUTH_REQUIRED);
            if (!saved.expiresAt().isAfter(clock.instant())) throw new ApiException(ErrorCode.TOKEN_EXPIRED);
            boolean restricted = member.status() != Status.ACTIVE || member.role() == null || member.emailVerifiedAt() == null;
            return new ActorPrincipal(member.id().toString(), ActorPrincipal.Kind.ADULT, null, null, null,
                    saved.id().toString(), restricted);
        });
    }

    private Credentials issue(UUID memberId, UUID sessionId, Instant sessionExpiry) {
        String refresh = TokenSecrets.refresh();
        jdbc.update("insert into identity.refresh_token (token_hash, session_id, created_at) values (?, ?, ?)",
                TokenSecrets.hash(refresh), sessionId, Timestamp.from(clock.instant()));
        return new Credentials(jwt.issue(memberId, sessionId, sessionExpiry), refresh);
    }

    private Session lockSession(UUID id) {
        // Lock member first, then session in all mutations to make future suspension compatible.
        var owners = jdbc.query("select member_id from identity.auth_session where id=?", (rs, row) -> rs.getObject(1, UUID.class), id);
        if (owners.isEmpty()) return null;
        Member member = byId(owners.getFirst(), true);
        var rows = jdbc.query("select id as session_id, expires_at, revoked_at from identity.auth_session where id=? for update",
                (rs, row) -> new Session(rs.getObject("session_id", UUID.class), member, instant(rs, "expires_at"), instant(rs, "revoked_at")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Member byId(UUID id, boolean lock) {
        var rows = jdbc.query("select * from identity.member where id=?" + (lock ? " for update" : ""), this::member, id);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.AUTH_REQUIRED);
        return rows.getFirst();
    }

    private Refresh findRefresh(String hash) {
        var rows = jdbc.query("select session_id, consumed_at from identity.refresh_token where token_hash=?",
                (rs, row) -> new Refresh(rs.getObject(1, UUID.class), instant(rs, "consumed_at")), hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void revoke(UUID id) {
        jdbc.update("update identity.auth_session set revoked_at=coalesce(revoked_at, ?) where id=?", Timestamp.from(clock.instant()), id);
    }

    private Member member(ResultSet rs, int row) throws SQLException {
        String role = rs.getString("role");
        return new Member(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getString("password_hash"), rs.getString("requested_role") == null ? null : RequestedRole.valueOf(rs.getString("requested_role")),
                role == null ? null : Role.valueOf(role), Status.valueOf(rs.getString("status")),
                instant(rs, "email_verified_at"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static boolean isRefreshToken(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    private static Credentials unwrap(Result result) {
        if (result.error() != null) throw new ApiException(result.error());
        return result.credentials();
    }

    private record Member(UUID id, String email, String displayName, String passwordHash, RequestedRole requestedRole,
                          Role role, Status status, Instant emailVerifiedAt, Instant createdAt) {
        MemberView view() { return new MemberView(id, email, displayName, requestedRole, role, status, emailVerifiedAt != null, createdAt); }
        @Override public String toString() { return "Member[redacted]"; }
    }
    private record Session(UUID id, Member member, Instant expiresAt, Instant revokedAt) {}
    private record Refresh(UUID sessionId, Instant consumedAt) {}
    private record Result(Credentials credentials, ErrorCode error) {
        static Result success(Credentials credentials) { return new Result(credentials, null); }
        static Result error(ErrorCode error) { return new Result(null, error); }
    }
}
