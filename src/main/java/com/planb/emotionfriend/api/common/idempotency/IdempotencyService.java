package com.planb.emotionfriend.api.common.idempotency;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.common.validation.RequestPreconditions;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final RequestFingerprint fingerprint;
    private final ReplayCipher cipher;
    private final IdempotencyProperties properties;
    private final TransactionTemplate transaction;

    public IdempotencyService(JdbcTemplate jdbc, RequestFingerprint fingerprint, ReplayCipher cipher,
                              IdempotencyProperties properties, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.fingerprint = fingerprint;
        this.cipher = cipher;
        this.properties = properties;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(30);
    }

    /**
     * Runs accessCheck before every first execution AND replay, inside the same transaction as operation.
     * accessCheck must resolve/lock current resource, consent, deletion and revocation state; never use cached grants.
     * All business writes must use this transaction's DataSource/JPA manager; external effects belong in an outbox.
     * Include all behavior-changing query parameters in requestBody. Never derive actor from a request field.
     */
    public StoredResponse execute(ActorPrincipal actor, String method, String path, String key, Object requestBody,
                                  Runnable accessCheck, Supplier<StoredResponse> operation) {
        if (actor == null) throw new ApiException(ErrorCode.AUTH_REQUIRED);
        RequestPreconditions.idempotencyKey(key);
        if (method == null || !Set.of("POST", "PUT", "PATCH", "DELETE").contains(method)
                || path == null || !path.startsWith("/") || path.length() > 1024 || path.contains("?")
                || path.codePoints().anyMatch(Character::isISOControl)) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        Objects.requireNonNull(accessCheck, "Current access check is required");
        Objects.requireNonNull(operation, "Business operation is required");
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || (isolation != null && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED)) {
            throw new IllegalStateException("Idempotency requires a writable READ_COMMITTED transaction");
        }
        String actorScope = fingerprint.actorScope(actor);
        String keyDigest = fingerprint.key(key);
        String requestDigest = fingerprint.request(requestBody);
        String scope = fingerprint.scope(actorScope, method, path, keyDigest);
        return Objects.requireNonNull(transaction.execute(status -> {
            // A transaction-scoped lock also serializes the first request, when no row exists yet.
            ByteBuffer lock = ByteBuffer.wrap(HexFormat.of().parseHex(scope));
            jdbc.query("select pg_advisory_xact_lock(?, ?)", rs -> {}, lock.getInt(), lock.getInt());
            accessCheck.run();
            var rows = jdbc.query("""
                    select request_digest, status_code, content_type, encrypted_body
                    from foundation.idempotency_record
                    where actor_scope=? and method=? and path=? and key_digest=?
                    """, (rs, row) -> new Saved(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getBytes(4)),
                    actorScope, method, path, keyDigest);
            if (!rows.isEmpty()) {
                Saved saved = rows.getFirst();
                if (!saved.requestDigest().equals(requestDigest)) throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
                return new StoredResponse(saved.status(), saved.contentType(),
                        cipher.decrypt(saved.body(), binding(scope, saved.status(), saved.contentType())));
            }
            StoredResponse result = Objects.requireNonNull(operation.get());
            if (result.body().length > properties.maxResponseBytes()) {
                throw new IllegalStateException("Accepted response exceeds replay storage limit");
            }
            byte[] encrypted = cipher.encrypt(result.body(), binding(scope, result.status(), result.contentType()));
            jdbc.update("""
                    insert into foundation.idempotency_record
                        (actor_scope, method, path, key_digest, request_digest, status_code, content_type, encrypted_body)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, actorScope, method, path, keyDigest, requestDigest, result.status(), result.contentType(), encrypted);
            return result;
        }));
    }

    private String binding(String scope, int status, String contentType) {
        return scope + ":" + status + ":" + (contentType == null ? "" : contentType);
    }

    private record Saved(String requestDigest, int status, String contentType, byte[] body) {
        @Override public String toString() { return "Saved[redacted]"; }
    }
}
