package com.planb.emotionfriend.api.mail;

import static org.assertj.core.api.Assertions.*;

import com.planb.emotionfriend.api.auth.TokenSecrets;
import com.planb.emotionfriend.api.common.FoundationIntegrationTest;
import com.planb.emotionfriend.api.common.idempotency.ReplayCipher;
import com.planb.emotionfriend.api.membership.AuditTrail;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EmailDeliveryIntegrationTest.FakeResend.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailDeliveryIntegrationTest {
    private static final List<WireRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final Queue<Reply> REPLIES = new ConcurrentLinkedQueue<>();
    private static final HttpServer PROVIDER = provider();
    private final HttpClient browser = HttpClient.newHttpClient();
    @Autowired EmailDelivery delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditTrail audit;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder passwords;
    @Autowired ReplayCipher cipher;
    @Autowired PlatformTransactionManager manager;
    @LocalServerPort int port;
    private String passwordHash;
    private static final String PASSWORD = "Synthetic mail test password 2026!";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        FoundationIntegrationTest.isolatedPostgres(registry);
        registry.add("emotion-friend.mail.enabled", () -> true);
        registry.add("emotion-friend.mail.scheduling-enabled", () -> false);
        registry.add("emotion-friend.mail.api-key", () -> "re_synthetic_test_key_12345");
        registry.add("emotion-friend.mail.from", () -> "no-reply@notify.example.test");
        registry.add("emotion-friend.mail.frontend-base-url", () -> "https://web.example.test");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeResend {
        @Bean
        @Primary
        MailSender fakeSender(MailProperties properties, JsonMapper mapper) {
            return new ResendMailSender(
                    properties,
                    mapper,
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                    URI.create("http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/emails"));
        }
    }

    @BeforeAll
    void hash() {
        passwordHash = passwords.encode(PASSWORD);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("truncate care.child,identity.member,identity.auth_rate_bucket cascade");
        REQUESTS.clear();
        REPLIES.clear();
    }

    @AfterAll
    void close() {
        browser.close();
        PROVIDER.stop(0);
    }

    @Test
    void emailRequestIsSentThroughResendHttpAndItsLinkVerifiesTheAccount() throws Exception {
        Pending pending = member();
        var login =
                api("/auth/login", Map.of("loginId", pending.email(), "password", PASSWORD), null);
        assertThat(login.statusCode()).isEqualTo(200);
        String access = json(login).path("credential").path("accessToken").asText();
        assertThat(api("/auth/email-verifications", Map.of(), access).statusCode()).isEqualTo(202);
        assertThat(delivery.dispatchBatch(10)).isEqualTo(1);
        WireRequest wire = REQUESTS.getFirst();
        var mail = mapper.readTree(wire.body());
        assertThat(wire.authorization()).isEqualTo("Bearer re_synthetic_test_key_12345");
        assertThat(wire.key()).startsWith("email-verification/");
        assertThat(mail.path("from").asText()).isEqualTo("no-reply@notify.example.test");
        assertThat(mail.path("to").get(0).asText()).isEqualTo(pending.email());
        assertThat(mail.path("text").asText())
                .contains("이메일 확인 완료")
                .doesNotContain("childId", "진단");
        String link =
                mail.path("text")
                        .asText()
                        .lines()
                        .filter(line -> line.startsWith("https://"))
                        .findFirst()
                        .orElseThrow();
        URI url = URI.create(link);
        assertThat(url.getRawQuery()).isNull();
        assertThat(url.getFragment()).startsWith("/verify-email?");
        Map<String, String> values = new HashMap<>();
        for (String pair : url.getFragment().split("\\?", 2)[1].split("&")) {
            String[] item = pair.split("=", 2);
            values.put(item[0], item[1]);
        }
        assertThat(api("/auth/email-verifications/confirm", values, access).statusCode())
                .isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "select email_verified_at is not null from identity.member where"
                                    + " id=?",
                                Boolean.class,
                                pending.member()))
                .isTrue();
        assertThat(delivery.dispatchBatch(10)).isZero();
        assertThat(REQUESTS).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select delivery_state from identity.notification_outbox",
                                String.class))
                .isEqualTo("SENT");
        assertThat(
                        jdbc.queryForObject(
                                "select provider_message_id from identity.notification_outbox",
                                String.class))
                .isEqualTo("synthetic-message-1");
    }

    @Test
    void temporaryFailureUsesBackoffAndIdenticalRequestAndIdempotencyKey() {
        Event event = event();
        REPLIES.add(new Reply(503, "{}", null));
        assertThat(delivery.dispatchBatch(10)).isEqualTo(1);
        assertThat(state(event)).isEqualTo("PENDING");
        assertThat(delivery.dispatchBatch(10)).isZero();
        due(event);
        delivery.dispatchBatch(10);
        assertThat(state(event)).isEqualTo("SENT");
        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS.get(0).key()).isEqualTo(REQUESTS.get(1).key());
        assertThat(REQUESTS.get(0).body()).isEqualTo(REQUESTS.get(1).body());
    }

    @Test
    void retryAfterIsRespected() {
        Event event = event();
        REPLIES.add(new Reply(429, "{}", "120"));
        delivery.dispatchBatch(10);
        Instant next =
                jdbc.queryForObject(
                                "select next_attempt_at from identity.notification_outbox where"
                                    + " id=?",
                                Timestamp.class,
                                event.outbox())
                        .toInstant();
        assertThat(next).isAfter(Instant.now().plusSeconds(110));
        assertThat(state(event)).isEqualTo("PENDING");
    }

    @Test
    void permanentProviderErrorDoesNotRetry() {
        Event event = event();
        REPLIES.add(new Reply(403, "{\"message\":\"not verified\"}", null));
        delivery.dispatchBatch(10);
        assertThat(state(event)).isEqualTo("FAILED");
        due(event);
        assertThat(delivery.dispatchBatch(10)).isZero();
        assertThat(REQUESTS).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select last_error_code from identity.notification_outbox",
                                String.class))
                .isEqualTo("PROVIDER_REJECTED");
    }

    @Test
    void retriesStopAfterFiveAttempts() {
        Event event = event();
        for (int i = 0; i < 5; i++) {
            REPLIES.add(new Reply(503, "{}", null));
            due(event);
            delivery.dispatchBatch(10);
        }
        assertThat(state(event)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select attempts from identity.notification_outbox", Integer.class))
                .isEqualTo(5);
        assertThat(delivery.dispatchBatch(10)).isZero();
        assertThat(REQUESTS).hasSize(5);
    }

    @Test
    void inactiveVerificationRequestsAreSkipped() {
        Event consumed = event();
        jdbc.update(
                "update identity.email_verification set consumed_at=current_timestamp where id=?",
                consumed.verification());
        Event expired = event();
        jdbc.update(
                "update identity.email_verification set expires_at=current_timestamp-interval '1"
                    + " second' where id=?",
                expired.verification());
        Event verified = event();
        jdbc.update(
                "update identity.member set email_verified_at=current_timestamp where id=?",
                verified.member());
        Event suspended = event();
        jdbc.update("update identity.member set status='SUSPENDED' where id=?", suspended.member());
        assertThat(delivery.dispatchBatch(10)).isEqualTo(4);
        assertThat(REQUESTS).isEmpty();
        for (Event event : List.of(consumed, expired, verified, suspended))
            assertThat(state(event)).isEqualTo("SKIPPED");
    }

    @Test
    void twoWorkersDoNotSendTheSamePendingEmail() throws Exception {
        Event event = event();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> delivery.dispatchBatch(10));
            var two = pool.submit(() -> delivery.dispatchBatch(10));
            assertThat(one.get(15, TimeUnit.SECONDS) + two.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(REQUESTS).hasSize(1);
        assertThat(state(event)).isEqualTo("SENT");
    }

    @Test
    void expiredLeaseRecoversWithTheOriginalRequest() {
        Event event = event();
        REPLIES.add(new Reply(503, "{}", null));
        delivery.dispatchBatch(10);
        jdbc.update(
                "update identity.notification_outbox set"
                    + " delivery_state='PROCESSING',lease_id=?,lease_until=current_timestamp-interval"
                    + " '1 second' where id=?",
                UUID.randomUUID(),
                event.outbox());
        delivery.dispatchBatch(10);
        assertThat(state(event)).isEqualTo("SENT");
        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS.getFirst().body()).isEqualTo(REQUESTS.getLast().body());
        assertThat(REQUESTS.getFirst().key()).isEqualTo(REQUESTS.getLast().key());
    }

    @Test
    void disabledModeDoesNotSend() {
        Event event = event();
        var off = new MailProperties(false, "", "", "");
        var dispatcher =
                new EmailDelivery(
                        jdbc,
                        cipher,
                        mapper,
                        Clock.systemUTC(),
                        off,
                        (message, key) -> {
                            throw new AssertionError("Provider called while disabled");
                        },
                        manager);
        assertThat(dispatcher.dispatchBatch(10)).isZero();
        assertThat(state(event)).isEqualTo("PENDING");
    }

    @Test
    void unrelatedNotificationsAreNotSent() {
        Pending pending = member();
        audit.notify(
                pending.member(),
                "THERAPIST_INVITATION",
                Map.of("invitationId", UUID.randomUUID()));
        assertThat(delivery.dispatchBatch(10)).isZero();
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void invalidPayloadDoesNotBlockTheQueue() {
        Event broken = event();
        jdbc.update(
                "update identity.notification_outbox set encrypted_payload=? where id=?",
                new byte[] {1, 2, 3},
                broken.outbox());
        Event healthy = event();
        assertThat(delivery.dispatchBatch(10)).isEqualTo(2);
        assertThat(state(broken)).isEqualTo("FAILED");
        assertThat(state(healthy)).isEqualTo("SENT");
        assertThat(REQUESTS).hasSize(1);
    }

    @Test
    void oversizedProviderBodyIsBoundedAndRetryable() {
        Event event = event();
        REPLIES.add(new Reply(200, "x".repeat(9000), null));
        delivery.dispatchBatch(10);
        assertThat(state(event)).isEqualTo("PENDING");
    }

    @Test
    void stalledResponseBodyDoesNotHoldTheWorkerIndefinitely() {
        Event event = event();
        REPLIES.add(new Reply(200, "{\"id\":\"late-message\"}", null, 9500));
        long started = System.nanoTime();
        delivery.dispatchBatch(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(11));
        assertThat(state(event)).isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "select last_error_code from identity.notification_outbox",
                                String.class))
                .isIn("NETWORK_TIMEOUT", "NETWORK_FAILURE");
    }

    private Pending member() {
        UUID id = UUID.randomUUID();
        String email = "mail-" + id + "@example.test";
        jdbc.update(
                "insert into"
                    + " identity.member(id,email,display_name,password_hash,requested_role,status,created_at,updated_at)"
                    + " values(?,?,'메일"
                    + " 테스트',?,'GUARDIAN','PENDING',current_timestamp,current_timestamp)",
                id,
                email,
                passwordHash);
        return new Pending(id, email);
    }

    private Event event() {
        Pending member = member();
        UUID verification = UUID.randomUUID();
        String token = TokenSecrets.refresh();
        jdbc.update(
                "insert into"
                    + " identity.email_verification(id,member_id,token_hash,expires_at,created_at)"
                    + " values(?,?,?,current_timestamp+interval '15 minutes',current_timestamp)",
                verification,
                member.member(),
                TokenSecrets.hash(token));
        audit.notify(
                member.member(),
                "EMAIL_VERIFICATION",
                Map.of("verificationId", verification, "token", token));
        UUID outbox =
                jdbc.queryForObject(
                        "select id from identity.notification_outbox where recipient_id=?",
                        UUID.class,
                        member.member());
        return new Event(member.member(), verification, outbox);
    }

    private void due(Event event) {
        jdbc.update(
                "update identity.notification_outbox set next_attempt_at=current_timestamp-interval"
                    + " '1 second' where id=?",
                event.outbox());
    }

    private String state(Event event) {
        return jdbc.queryForObject(
                "select delivery_state from identity.notification_outbox where id=?",
                String.class,
                event.outbox());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> api(String path, Object body, String access) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (access != null) request.header("Authorization", "Bearer " + access);
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/emails",
                    exchange -> {
                        String body =
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8);
                        REQUESTS.add(
                                new WireRequest(
                                        exchange.getRequestHeaders().getFirst("Authorization"),
                                        exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                                        body));
                        Reply reply = REPLIES.poll();
                        if (reply == null)
                            reply = new Reply(200, "{\"id\":\"synthetic-message-1\"}", null);
                        if (reply.after() != null)
                            exchange.getResponseHeaders().add("Retry-After", reply.after());
                        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(reply.status(), bytes.length);
                        if (reply.delayMillis() > 0) {
                            try {
                                Thread.sleep(reply.delayMillis());
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        try (var output = exchange.getResponseBody()) {
                            output.write(bytes);
                        }
                        exchange.close();
                    });
            server.start();
            return server;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot start local fake provider");
        }
    }

    private record Pending(UUID member, String email) {}

    private record Event(UUID member, UUID verification, UUID outbox) {}

    private record WireRequest(String authorization, String key, String body) {
        @Override
        public String toString() {
            return "WireRequest[synthetic-redacted]";
        }
    }

    private record Reply(int status, String body, String after, long delayMillis) {
        Reply(int status, String body, String after) {
            this(status, body, after, 0);
        }
    }
}
