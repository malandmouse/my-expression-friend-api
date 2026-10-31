package com.planb.emotionfriend.api.membership;

import com.planb.emotionfriend.api.common.idempotency.ReplayCipher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Component
public class AuditTrail {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final ReplayCipher cipher;
    private final Clock clock;

    public AuditTrail(JdbcTemplate jdbc, JsonMapper mapper, ReplayCipher cipher, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
        this.clock = clock;
    }

    public void record(
            UUID actor, String action, UUID target, String reason, Object before, Object after) {
        jdbc.update(
                "insert into identity.audit_event (id, actor_id, action, target_id, reason,"
                    + " before_state, after_state, recorded_at) values (?, ?, ?, ?, ?, ?::jsonb,"
                    + " ?::jsonb, ?)",
                UUID.randomUUID(),
                actor,
                action,
                target,
                reason,
                mapper.writeValueAsString(before),
                mapper.writeValueAsString(after),
                Timestamp.from(clock.instant()));
    }

    public void notify(UUID recipient, String kind, Map<String, ?> payload) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into identity.notification_outbox (id, recipient_id, kind,"
                        + " encrypted_payload, created_at) values (?, ?, ?, ?, ?)",
                id,
                recipient,
                kind,
                cipher.encrypt(
                        mapper.writeValueAsBytes(payload),
                        "notification:" + id + ":" + recipient + ":" + kind),
                Timestamp.from(clock.instant()));
    }
}
