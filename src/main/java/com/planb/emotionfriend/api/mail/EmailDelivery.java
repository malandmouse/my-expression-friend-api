package com.planb.emotionfriend.api.mail;

import com.planb.emotionfriend.api.auth.TokenSecrets;
import com.planb.emotionfriend.api.common.idempotency.ReplayCipher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class EmailDelivery {
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbc;
    private final ReplayCipher cipher;
    private final JsonMapper mapper;
    private final Clock clock;
    private final MailProperties properties;
    private final MailSender sender;
    private final TransactionTemplate transaction;

    public EmailDelivery(
            JdbcTemplate jdbc,
            ReplayCipher cipher,
            JsonMapper mapper,
            Clock clock,
            MailProperties properties,
            MailSender sender,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mapper = mapper;
        this.clock = clock;
        this.properties = properties;
        this.sender = sender;
        this.transaction = new TransactionTemplate(manager);
    }

    public int dispatchBatch(int limit) {
        if (limit < 1 || limit > 10)
            throw new IllegalArgumentException("Invalid delivery batch size");
        if (!properties.enabled()) return 0;
        int processed = 0;
        for (int i = 0; i < limit; i++) {
            Claim claim = transaction.execute(status -> claim());
            if (claim == null) break;
            processed++;
            if (claim.message() == null) continue;
            MailSender.Result result;
            try {
                result = sender.send(claim.message(), "email-verification/" + claim.id());
            } catch (RuntimeException failure) {
                result = MailSender.Result.retry("SENDER_FAILURE", null);
            }
            MailSender.Result completed = result;
            transaction.executeWithoutResult(status -> complete(claim, completed));
        }
        return processed;
    }

    private Claim claim() {
        var pending =
                jdbc.query(
                        """
select id,recipient_id,kind,encrypted_payload,encrypted_request,attempts
from identity.notification_outbox where kind='EMAIL_VERIFICATION' and delivered_at is null and
((delivery_state='PENDING' and next_attempt_at<=?) or (delivery_state='PROCESSING' and lease_until<=?))
order by created_at,id for update skip locked limit 1
""",
                        (rs, row) ->
                                new Row(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getBytes(4),
                                        rs.getBytes(5),
                                        rs.getInt(6)),
                        now(),
                        now());
        if (pending.isEmpty()) return null;
        Row row = pending.getFirst();
        if (row.attempts() >= MAX_ATTEMPTS) {
            finishWithoutSend(row.id(), "FAILED", "ATTEMPTS_EXHAUSTED");
            return new Claim(row.id(), null, 0, null);
        }
        try {
            var payload =
                    mapper.readTree(
                            cipher.decrypt(
                                    row.payload(),
                                    "notification:"
                                            + row.id()
                                            + ":"
                                            + row.recipient()
                                            + ":"
                                            + row.kind()));
            UUID verification = UUID.fromString(payload.path("verificationId").asText());
            String token = payload.path("token").asText();
            if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            var recipient =
                    jdbc.query(
                            """
select m.email from identity.email_verification v join identity.member m on m.id=v.member_id
where v.id=? and v.member_id=? and v.token_hash=? and v.consumed_at is null and v.expires_at>?
and m.email_verified_at is null and m.status<>'SUSPENDED'
""",
                            (rs, index) -> rs.getString(1),
                            verification,
                            row.recipient(),
                            TokenSecrets.hash(token),
                            now());
            if (recipient.isEmpty()) {
                finishWithoutSend(row.id(), "SKIPPED", "VERIFICATION_INACTIVE");
                return new Claim(row.id(), null, 0, null);
            }
            MailSender.Message message;
            byte[] encrypted = row.request();
            if (encrypted == null) {
                message =
                        new MailSender.Message(
                                properties.from(),
                                recipient.getFirst(),
                                "[나의 표정 친구] 이메일 주소를 확인해 주세요",
                                "나의 표정 친구 가입 이메일을 확인해 주세요.\n\n"
                                        + properties.verificationLink(
                                                verification.toString(), token)
                                        + "\n\n"
                                        + "링크를 연 뒤 가입한 계정으로 로그인하고 ‘이메일 확인 완료’를 눌러 주세요.\n"
                                        + "확인 정보는 요청 시점부터 15분 동안 한 번 사용할 수 있습니다.\n"
                                        + "본인이 요청하지 않았다면 이 메일을 무시해 주세요.");
                encrypted =
                        cipher.encrypt(
                                mapper.writeValueAsBytes(message),
                                "mail-request:" + row.id() + ":" + row.recipient());
            } else
                message =
                        mapper.readValue(
                                cipher.decrypt(
                                        encrypted,
                                        "mail-request:" + row.id() + ":" + row.recipient()),
                                MailSender.Message.class);
            if (!message.to().equals(recipient.getFirst())) {
                finishWithoutSend(row.id(), "SKIPPED", "RECIPIENT_CHANGED");
                return new Claim(row.id(), null, 0, null);
            }
            UUID lease = UUID.randomUUID();
            jdbc.update(
                    "update identity.notification_outbox set"
                        + " delivery_state='PROCESSING',attempts=attempts+1,lease_id=?,lease_until=?,encrypted_request=?,last_error_code=null"
                        + " where id=?",
                    lease,
                    Timestamp.from(clock.instant().plusSeconds(60)),
                    encrypted,
                    row.id());
            return new Claim(row.id(), lease, row.attempts() + 1, message);
        } catch (IllegalArgumentException
                | IllegalStateException
                | tools.jackson.core.JacksonException failure) {
            finishWithoutSend(row.id(), "FAILED", "INVALID_PAYLOAD");
            return new Claim(row.id(), null, 0, null);
        }
    }

    private void complete(Claim claim, MailSender.Result result) {
        if (result.outcome() == MailSender.Outcome.ACCEPTED) {
            jdbc.update(
                    "update identity.notification_outbox set"
                        + " delivery_state='SENT',delivered_at=?,provider_message_id=?,lease_id=null,lease_until=null,last_error_code=null"
                        + " where id=? and lease_id=?",
                    now(),
                    result.providerId(),
                    claim.id(),
                    claim.lease());
            return;
        }
        boolean retry =
                result.outcome() == MailSender.Outcome.RETRY && claim.attempt() < MAX_ATTEMPTS;
        long delay =
                switch (claim.attempt()) {
                    case 1 -> 5;
                    case 2 -> 30;
                    case 3 -> 120;
                    default -> 300;
                };
        if (result.retryAfter() != null)
            delay = Math.max(delay, Math.clamp(result.retryAfter().toSeconds(), 1, 900));
        jdbc.update(
                "update identity.notification_outbox set"
                    + " delivery_state=?,next_attempt_at=?,lease_id=null,lease_until=null,last_error_code=?"
                    + " where id=? and lease_id=?",
                retry ? "PENDING" : "FAILED",
                Timestamp.from(clock.instant().plusSeconds(delay)),
                result.code(),
                claim.id(),
                claim.lease());
    }

    private void finishWithoutSend(UUID id, String state, String code) {
        jdbc.update(
                "update identity.notification_outbox set"
                    + " delivery_state=?,last_error_code=?,lease_id=null,lease_until=null where"
                    + " id=?",
                state,
                code,
                id);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private record Row(
            UUID id, UUID recipient, String kind, byte[] payload, byte[] request, int attempts) {
        @Override
        public String toString() {
            return "MailRow[redacted]";
        }
    }

    private record Claim(UUID id, UUID lease, int attempt, MailSender.Message message) {
        @Override
        public String toString() {
            return "MailClaim[redacted]";
        }
    }
}
