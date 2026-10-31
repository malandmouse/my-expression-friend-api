package com.planb.emotionfriend.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.planb.emotionfriend.api.common.FoundationIntegrationTest;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {
    private static final String PASSWORD = "a long synthetic password 2026!";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder passwords;

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry registry) { FoundationIntegrationTest.isolatedPostgres(registry); }

    @BeforeEach
    void reset() {
        jdbc.execute("truncate care.child, identity.member, identity.auth_rate_bucket cascade");
        jdbc.update("delete from identity.refresh_token");
        jdbc.update("delete from identity.auth_session");
        jdbc.update("delete from identity.member");
        jdbc.update("delete from identity.auth_rate_bucket");
    }

    @AfterAll static void closeClient() { CLIENT.close(); }

    @Test
    void signupLoginMeRefreshLogoutRoundTripAndOnlyHashesArePersisted() throws Exception {
        var signup = signup("guardian@example.test", "GUARDIAN");
        assertThat(signup.statusCode()).isEqualTo(201);
        JsonNode member = json(signup);
        assertThat(member.path("status").asText()).isEqualTo("PENDING");
        assertThat(member.path("role").isNull()).isTrue();
        assertThat(member.path("emailVerified").asBoolean()).isFalse();
        assertThat(signup.body()).doesNotContain("password", "passwordHash");
        String hash = jdbc.queryForObject("select password_hash from identity.member", String.class);
        assertThat(hash).startsWith("{pbkdf2-sha256}").doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        var login = login("GUARDIAN@EXAMPLE.TEST");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat(login.headers().firstValue("set-cookie")).isEmpty();
        Tokens first = tokens(login);
        assertThat(first.access().split("\\.")).hasSize(3);
        assertThat(first.refresh()).hasSize(43);
        assertThat(jdbc.queryForObject("select token_hash from identity.refresh_token", String.class))
                .isEqualTo(TokenSecrets.hash(first.refresh())).isNotEqualTo(first.refresh());
        var me = get("/api/v1/me", first.access());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json(me).path("id").asText()).isEqualTo(member.path("id").asText());
        var refresh = post("/api/v1/auth/refresh", Map.of("refreshToken", first.refresh()), null);
        assertThat(refresh.statusCode()).isEqualTo(200);
        Tokens second = tokens(refresh);
        assertThat(second.access()).isNotEqualTo(first.access());
        assertThat(second.refresh()).isNotEqualTo(first.refresh());
        assertThat(jdbc.queryForObject("select count(*) from identity.refresh_token where consumed_at is not null", Integer.class)).isEqualTo(1);
        var logout = post("/api/v1/auth/logout", Map.of("refreshToken", second.refresh()), second.access());
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertThat(get("/api/v1/me", first.access()).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/me", second.access()).statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", second.refresh()), null).statusCode()).isEqualTo(401);
    }

    @Test
    void pendingAndRejectedCanSeeOnlyTheirStatusAndCannotUseBusinessApis() throws Exception {
        signup("pending@example.test", "THERAPIST");
        Tokens tokens = tokens(login("pending@example.test"));
        var denied = get("/api/v1/children", tokens.access());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).path("code").asText()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        jdbc.update("update identity.member set status='REJECTED'");
        assertThat(json(get("/api/v1/me", tokens.access())).path("status").asText()).isEqualTo("REJECTED");
        assertThat(get("/api/v1/children", tokens.access()).statusCode()).isEqualTo(403);
    }

    @Test
    void signupCannotAssignAdminOrInjectApprovalAndEmailIsCaseInsensitive() throws Exception {
        assertThat(signup("ADMIN@example.test", "ADMIN").statusCode()).isEqualTo(422);
        var injected = new HashMap<String, Object>(signupBody("guardian@example.test", "GUARDIAN"));
        injected.put("role", "ADMIN");
        injected.put("status", "ACTIVE");
        assertThat(post("/api/v1/auth/signup", injected, null).statusCode()).isEqualTo(422);
        assertThat(signup("guardian@example.test", "GUARDIAN").statusCode()).isEqualTo(201);
        var duplicate = signup("GUARDIAN@EXAMPLE.TEST", "THERAPIST");
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).path("code").asText()).isEqualTo("ACCOUNT_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("select count(*) from identity.member", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSignupCreatesOnePendingAccount() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> call = () -> { start.await(); return signup("race@example.test", "GUARDIAN").statusCode(); };
            Future<Integer> one = pool.submit(call);
            Future<Integer> two = pool.submit(call);
            start.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from identity.member", Integer.class)).isEqualTo(1);
    }

    @Test
    void loginFailuresAreGenericAndDoNotIssueSessions() throws Exception {
        signup("known@example.test", "GUARDIAN");
        var wrong = post("/api/v1/auth/login", Map.of("loginId", "known@example.test", "password", "wrong password"), null);
        var unknown = login("unknown@example.test");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(json(wrong).path("code").asText()).isEqualTo(json(unknown).path("code").asText());
        assertThat(wrong.body()).doesNotContain("known@example.test", "wrong password", "password_hash");
        assertThat(jdbc.queryForObject("select count(*) from identity.auth_session", Integer.class)).isZero();
    }

    @Test
    void usedRefreshRevokesFamilyAndCommitSurvivesUnauthorizedResponse() throws Exception {
        signup("reuse@example.test", "GUARDIAN");
        Tokens first = tokens(login("reuse@example.test"));
        Tokens second = tokens(post("/api/v1/auth/refresh", Map.of("refreshToken", first.refresh()), null));
        var replay = post("/api/v1/auth/refresh", Map.of("refreshToken", first.refresh()), null);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select revoked_at is not null from identity.auth_session", Boolean.class)).isTrue();
        assertThat(get("/api/v1/me", second.access()).statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", second.refresh()), null).statusCode()).isEqualTo(401);
    }

    @Test
    void concurrentRefreshAllowsOneRotationAndReuseRevokesTheWinner() throws Exception {
        signup("rotation@example.test", "THERAPIST");
        Tokens first = tokens(login("rotation@example.test"));
        List<HttpResponse<String>> responses;
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<HttpResponse<String>> call = () -> { start.await(); return post("/api/v1/auth/refresh", Map.of("refreshToken", first.refresh()), null); };
            var one = pool.submit(call);
            var two = pool.submit(call);
            start.countDown();
            responses = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 401);
        Tokens winner = tokens(responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow());
        assertThat(get("/api/v1/me", winner.access()).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from identity.refresh_token", Integer.class)).isEqualTo(2);
    }

    @Test
    void logoutCannotTargetAnotherLoginSessionEvenForTheSameMember() throws Exception {
        signup("sessions@example.test", "GUARDIAN");
        Tokens first = tokens(login("sessions@example.test"));
        Tokens other = tokens(login("sessions@example.test"));
        assertThat(post("/api/v1/auth/logout", Map.of("refreshToken", other.refresh()), first.access()).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/me", other.access()).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/auth/logout", Map.of("refreshToken", first.refresh()), first.access()).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/me", other.access()).statusCode()).isEqualTo(200);
    }

    @Test
    void suspensionImmediatelyBlocksExistingAccessAndRefreshAndNewLogin() throws Exception {
        signup("suspend@example.test", "GUARDIAN");
        Tokens first = tokens(login("suspend@example.test"));
        jdbc.update("update identity.member set status='SUSPENDED'");
        assertThat(get("/api/v1/me", first.access()).statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", first.refresh()), null).statusCode()).isEqualTo(401);
        assertThat(login("suspend@example.test").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select revoked_at is not null from identity.auth_session", Boolean.class)).isTrue();
    }

    @Test
    void currentApprovalIsReadFromDbAndDoesNotGrantChildAccess() throws Exception {
        signup("approved@example.test", "GUARDIAN");
        Tokens first = tokens(login("approved@example.test"));
        // Test fixture only: approval/email verification APIs are deliberately not part of this implementation.
        jdbc.update("update identity.member set status='ACTIVE', role=requested_role, email_verified_at=current_timestamp");
        var me = json(get("/api/v1/me", first.access()));
        assertThat(me.path("role").asText()).isEqualTo("GUARDIAN");
        assertThat(me.path("emailVerified").asBoolean()).isTrue();
        assertThat(me.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(get("/api/v1/children", first.access()).statusCode()).isEqualTo(200);
        assertThat(json(get("/api/v1/children", first.access())).path("items").size()).isZero();
        String payload = new String(Base64.getUrlDecoder().decode(first.access().split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain("email", "GUARDIAN", "permissions", "children");
    }

    @Test
    void expiredSessionCannotRefreshAndForgedAccessIsRejected() throws Exception {
        signup("expiry@example.test", "THERAPIST");
        Tokens first = tokens(login("expiry@example.test"));
        assertThat(get("/api/v1/me", first.access() + "x").statusCode()).isEqualTo(401);
        jdbc.update("update identity.auth_session set created_at=current_timestamp-interval '2 days', expires_at=current_timestamp-interval '1 day'");
        var response = post("/api/v1/auth/refresh", Map.of("refreshToken", first.refresh()), null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).path("code").asText()).isEqualTo("TOKEN_EXPIRED");
        assertThat(get("/api/v1/me", first.access()).statusCode()).isEqualTo(401);
    }

    @Test
    void validationRejectsWeakPasswordMalformedEmailAndBodyLeaksNoSecrets() throws Exception {
        var body = new HashMap<String, Object>(signupBody("not-an-email", "GUARDIAN"));
        body.put("password", "short");
        var response = post("/api/v1/auth/signup", body, null);
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).doesNotContain("not-an-email", "short");
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", "unknown"), null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    void rateLimitIsPersistedAcrossFailedRequestsAndSkipsPasswordWork() throws Exception {
        jdbc.update("insert into identity.auth_rate_bucket (bucket_key, window_start, attempts) values (?, current_timestamp, 20)",
                TokenSecrets.hash("login-account:limited@example.test"));
        var response = login("limited@example.test");
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(json(response).path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(login("limited@example.test").statusCode()).isEqualTo(429);
        assertThat(jdbc.queryForObject("select count(*) from identity.auth_session", Integer.class)).isZero();
    }

    @Test
    void swaggerListsImplementedAuthAndPublicEndpointsNeedNoBearer() throws Exception {
        JsonNode paths = json(get("/v3/api-docs", null)).path("paths");
        for (String path : List.of("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh")) {
            assertThat(paths.path(path).path("post").path("security").isArray()).as(path).isTrue();
            assertThat(paths.path(path).path("post").path("security").size()).as(path).isZero();
        }
        assertThat(paths.path("/api/v1/me").path("get").path("security").toString()).contains("adultBearer").doesNotContain("childBearer");
        assertThat(paths.path("/api/v1/auth/logout").path("post").path("security").toString()).contains("adultBearer");
    }

    private Map<String, Object> signupBody(String email, String role) {
        return Map.of("email", email, "displayName", "테스트 사용자", "password", PASSWORD, "requestedRole", role);
    }
    private HttpResponse<String> signup(String email, String role) throws Exception { return post("/api/v1/auth/signup", signupBody(email, role), null); }
    private HttpResponse<String> login(String email) throws Exception { return post("/api/v1/auth/login", Map.of("loginId", email, "password", PASSWORD), null); }
    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private Tokens tokens(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = json(response);
        return new Tokens(json.path("credential").path("accessToken").asText(), json.path("refreshToken").asText());
    }
    private HttpResponse<String> post(String path, Object body, String access) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (access != null) request.header("Authorization", "Bearer " + access);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path, String access) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).GET();
        if (access != null) request.header("Authorization", "Bearer " + access);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private record Tokens(String access, String refresh) { @Override public String toString() { return "Tokens[redacted]"; } }
}
