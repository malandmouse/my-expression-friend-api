package com.planb.emotionfriend.api.common;

import static org.assertj.core.api.Assertions.*;

import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class FoundationIntegrationTest {
    private static final ActorPrincipal ACTOR = new ActorPrincipal("synthetic-adult", ActorPrincipal.Kind.ADULT, null, null, null);
    private static final String PATH = "/api/v1/synthetic-operation";
    @Autowired IdempotencyService idempotency;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;
    @LocalServerPort int port;

    @DynamicPropertySource
    public static void isolatedPostgres(DynamicPropertyRegistry registry) {
        String url = System.getenv("EF_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/foundation_test")) {
            throw new IllegalStateException("EF_TEST_DB_URL must explicitly name the isolated local foundation_test database");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "foundation_test");
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("EF_TEST_DB_PASSWORD", ""));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/test-migration");
        registry.add("emotion-friend.idempotency.fingerprint-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("emotion-friend.idempotency.encryption-key", () -> Base64.getEncoder().encodeToString("1".repeat(32).getBytes(StandardCharsets.US_ASCII)));
        registry.add("emotion-friend.auth.signing-key", () -> Base64.getEncoder().encodeToString("2".repeat(32).getBytes(StandardCharsets.US_ASCII)));
        registry.add("emotion-friend.mail.enabled", () -> false);
    }

    @BeforeEach
    void resetIsolatedTestData() {
        jdbc.update("delete from foundation.idempotency_record");
        jdbc.update("delete from test_support.business_effect");
        jdbc.update("delete from test_support.access_state");
        jdbc.update("insert into test_support.access_state (id, revoked) values (1, false)");
    }

    private void currentAccess() {
        if (Boolean.TRUE.equals(jdbc.queryForObject("select revoked from test_support.access_state where id=1 for share", Boolean.class))) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    private StoredResponse effect(int amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into test_support.business_effect (id, amount) values (?, ?)", id, amount);
        return StoredResponse.json(mapper, 201, Map.of("id", id.toString(), "credential", "synthetic-secret"));
    }

    private int effects() { return jdbc.queryForObject("select count(*) from test_support.business_effect", Integer.class); }
    private int records() { return jdbc.queryForObject("select count(*) from foundation.idempotency_record", Integer.class); }

    @Test
    void realServerStartsWithFlywayAndDenyByDefaultSecurity() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='1' and success", Integer.class)).isEqualTo(1);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/children"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH_REQUIRED");
            var forged = client.send(HttpRequest.newBuilder(request.uri()).header("Authorization", "Bearer forged-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(forged.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("set-cookie")).isEmpty();
            var health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).doesNotContain("jdbc:", "foundation_test");
        }
    }

    @Test
    void openApiDescribesRuntimeEndpointsAndPublicHealth() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var api = mapper.readTree(response.body());
            assertThat(api.path("info").path("title").asText()).isEqualTo("표정친구 백엔드 API");
            assertThat(api.path("components").path("securitySchemes").path("adultBearer").path("scheme").asText()).isEqualTo("bearer");
            assertThat(api.path("components").path("securitySchemes").path("childBearer").path("scheme").asText()).isEqualTo("bearer");
            assertThat(api.path("paths").has("/actuator/health")).isTrue();
            assertThat(api.path("paths").has("/error")).isFalse();
            assertThat(api.path("paths").has("/api/v1/auth/login")).isTrue();
            assertThat(api.path("paths").path("/actuator/health").path("get").path("security").isArray()).isTrue();
            assertThat(api.path("paths").path("/actuator/health").path("get").path("security").size()).isZero();
        }
    }

    @Test
    void swaggerUiAssetsAndConfigurationAreAccessibleWithoutAuthentication() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var root = "http://127.0.0.1:" + port;
            var redirect = client.send(HttpRequest.newBuilder(URI.create(root + "/swagger-ui.html")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(redirect.statusCode()).isEqualTo(302);
            assertThat(redirect.headers().firstValue("location")).hasValue("/swagger-ui/index.html");
            var ui = client.send(HttpRequest.newBuilder(URI.create(root + "/swagger-ui/index.html")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(ui.statusCode()).isEqualTo(200);
            assertThat(ui.body()).contains("Swagger UI");
            var asset = client.send(HttpRequest.newBuilder(URI.create(root + "/swagger-ui/swagger-ui-bundle.js")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(asset.statusCode()).isEqualTo(200);
            assertThat(asset.body()).contains("SwaggerUIBundle");
            var config = client.send(HttpRequest.newBuilder(URI.create(root + "/v3/api-docs/swagger-config")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(config.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(config.body()).path("url").asText()).isEqualTo("/v3/api-docs");
            assertThat(config.body()).doesNotContain("petstore.swagger.io");
            var yaml = client.send(HttpRequest.newBuilder(URI.create(root + "/v3/api-docs.yaml")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(yaml.statusCode()).isEqualTo(200);
            assertThat(yaml.body()).contains("openapi:");
        }
    }

    @Test
    void sameRequestReplaysExactBytesAndRechecksCurrentAccess() {
        AtomicInteger checks = new AtomicInteger();
        Runnable check = () -> { currentAccess(); checks.incrementAndGet(); };
        StoredResponse first = idempotency.execute(ACTOR, "POST", PATH, "same-key", Map.of("value", 1), check, () -> effect(1));
        StoredResponse second = idempotency.execute(ACTOR, "POST", PATH, "same-key", Map.of("value", 1), check,
                () -> { throw new AssertionError("Replay executed business operation"); });
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(checks).hasValue(2);
        assertThat(effects()).isEqualTo(1);
        assertThat(records()).isEqualTo(1);
        byte[] stored = jdbc.queryForObject("select encrypted_body from foundation.idempotency_record", byte[].class);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("synthetic-secret");
        assertThat(jdbc.queryForObject("select key_digest from foundation.idempotency_record", String.class)).isNotEqualTo("same-key");
    }

    @Test
    void objectPropertyOrderDoesNotCreateFalseConflict() {
        var firstBody = new LinkedHashMap<String, Object>();
        firstBody.put("a", 1); firstBody.put("nested", Map.of("z", 2, "b", 3));
        var secondBody = new LinkedHashMap<String, Object>();
        secondBody.put("nested", Map.of("b", 3, "z", 2)); secondBody.put("a", 1);
        idempotency.execute(ACTOR, "POST", PATH, "ordered", firstBody, this::currentAccess, () -> effect(1));
        idempotency.execute(ACTOR, "POST", PATH, "ordered", secondBody, this::currentAccess, () -> effect(2));
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void changedBodyWithSameScopeConflictsWithoutNewEffect() {
        idempotency.execute(ACTOR, "POST", PATH, "conflict", Map.of("value", 1), this::currentAccess, () -> effect(1));
        assertThatThrownBy(() -> idempotency.execute(ACTOR, "POST", PATH, "conflict", Map.of("value", 2), this::currentAccess, () -> effect(2)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void concurrentFirstRequestsCommitOneEffect() throws Exception {
        AtomicInteger operations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<StoredResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) calls.add(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return idempotency.execute(ACTOR, "POST", PATH, "concurrent", Map.of("value", 1), this::currentAccess,
                        () -> { operations.incrementAndGet(); return effect(1); });
            });
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            byte[] first = futures.getFirst().get(10, TimeUnit.SECONDS).body();
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS).body()).isEqualTo(first);
        }
        assertThat(operations).hasValue(1);
        assertThat(effects()).isEqualTo(1);
        assertThat(records()).isEqualTo(1);
    }

    @Test
    void revokedAccessBlocksEvenAnAcceptedReplay() {
        idempotency.execute(ACTOR, "POST", PATH, "revoked", Map.of(), this::currentAccess, () -> effect(1));
        jdbc.update("update test_support.access_state set revoked=true where id=1");
        assertThatThrownBy(() -> idempotency.execute(ACTOR, "POST", PATH, "revoked", Map.of(), this::currentAccess, () -> effect(2)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.CONSENT_REQUIRED));
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void deniedFirstRequestCreatesNeitherRecordNorEffect() {
        jdbc.update("update test_support.access_state set revoked=true where id=1");
        assertThatThrownBy(() -> idempotency.execute(ACTOR, "POST", PATH, "denied", Map.of(), this::currentAccess, () -> effect(1)))
                .isInstanceOf(ApiException.class);
        assertThat(records()).isZero();
        assertThat(effects()).isZero();
    }

    @Test
    void businessFailureRollsBackBothJdbcAndJpaChangesAndAllowsRetry() {
        assertThatThrownBy(() -> idempotency.execute(ACTOR, "POST", PATH, "rollback", Map.of(), this::currentAccess, () -> {
            effect(1);
            entityManager.persist(new BusinessEffect(UUID.randomUUID(), 2));
            entityManager.flush();
            throw new IllegalStateException("synthetic-failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(effects()).isZero();
        assertThat(records()).isZero();
        idempotency.execute(ACTOR, "POST", PATH, "rollback", Map.of(), this::currentAccess, () -> effect(1));
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void replayInsertFailureRollsBackTheBusinessCommit() {
        jdbc.execute("alter table foundation.idempotency_record add constraint synthetic_reject check (status_code <> 201)");
        try {
            assertThatThrownBy(() -> idempotency.execute(ACTOR, "POST", PATH, "store-failure", Map.of(), this::currentAccess, () -> effect(1)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(effects()).isZero();
            assertThat(records()).isZero();
        } finally {
            jdbc.execute("alter table foundation.idempotency_record drop constraint synthetic_reject");
        }
    }

    @Test
    void sameKeyIsIsolatedByActorMethodAndPath() {
        idempotency.execute(ACTOR, "POST", PATH, "scoped", Map.of(), this::currentAccess, () -> effect(1));
        var other = new ActorPrincipal("synthetic-other", ActorPrincipal.Kind.ADULT, null, null, null);
        idempotency.execute(other, "POST", PATH, "scoped", Map.of(), this::currentAccess, () -> effect(2));
        idempotency.execute(ACTOR, "PUT", PATH, "scoped", Map.of(), this::currentAccess, () -> effect(3));
        idempotency.execute(ACTOR, "POST", PATH + "/other", "scoped", Map.of(), this::currentAccess, () -> effect(4));
        assertThat(effects()).isEqualTo(4);
        assertThat(records()).isEqualTo(4);
    }

    @Test
    void outerTransactionRollbackAlsoRollsBackIdempotencyAndBusinessWrites() {
        var outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            idempotency.execute(ACTOR, "POST", PATH, "outer", Map.of(), this::currentAccess, () -> effect(1));
            status.setRollbackOnly();
        });
        assertThat(effects()).isZero();
        assertThat(records()).isZero();
    }

    @Test
    void noContentIsReplayedWithoutJsonBody() {
        StoredResponse first = idempotency.execute(ACTOR, "POST", PATH, "204", Map.of(), this::currentAccess, StoredResponse::noContent);
        StoredResponse replay = idempotency.execute(ACTOR, "POST", PATH, "204", Map.of(), this::currentAccess, () -> effect(1));
        assertThat(first.status()).isEqualTo(204);
        assertThat(replay.status()).isEqualTo(204);
        assertThat(replay.body()).isEmpty();
        assertThat(replay.contentType()).isNull();
        assertThat(effects()).isZero();
    }

    @Test
    void tamperedStoredResponseCannotBeReplayed() {
        idempotency.execute(ACTOR, "POST", PATH, "tamper", Map.of(), this::currentAccess, () -> effect(1));
        jdbc.update("update foundation.idempotency_record set status_code=202");
        assertThatThrownBy(() -> idempotency.execute(ACTOR, "POST", PATH, "tamper", Map.of(), this::currentAccess, () -> effect(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(effects()).isEqualTo(1);
    }
}
