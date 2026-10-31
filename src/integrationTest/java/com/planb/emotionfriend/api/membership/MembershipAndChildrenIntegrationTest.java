package com.planb.emotionfriend.api.membership;

import static org.assertj.core.api.Assertions.*;

import com.planb.emotionfriend.api.children.ChildService;
import com.planb.emotionfriend.api.common.FoundationIntegrationTest;
import com.planb.emotionfriend.api.common.idempotency.ReplayCipher;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipAndChildrenIntegrationTest {
    private static final String PASSWORD = "a long synthetic password 2026!";
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper mapper;
    @Autowired ReplayCipher cipher;
    @Autowired MemberGuard guard;
    @Autowired AuditTrail audit;
    @Autowired PlatformTransactionManager manager;
    @Autowired Clock clock;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private String hash;
    private User admin, guardian, otherGuardian, therapist, otherTherapist;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        FoundationIntegrationTest.isolatedPostgres(registry);
    }

    @BeforeAll
    void prepareHash() {
        hash = passwords.encode(PASSWORD);
    }

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate care.child,identity.member,identity.auth_rate_bucket cascade");
        jdbc.update("delete from foundation.idempotency_record");
        admin = seed("admin@example.test", "ADMIN", true);
        guardian = seed("guardian@example.test", "GUARDIAN", true);
        otherGuardian = seed("other-guardian@example.test", "GUARDIAN", true);
        therapist = seed("therapist@example.test", "THERAPIST", true);
        otherTherapist = seed("other-therapist@example.test", "THERAPIST", true);
    }

    @AfterAll
    void close() {
        client.close();
    }

    @Test
    void emailVerificationIsAccountBoundSingleUseAndSecretIsEncrypted() throws Exception {
        User pending = seed("pending@example.test", "GUARDIAN", false);
        assertThat(
                        send("POST", "/api/v1/auth/email-verifications", Map.of(), pending, null)
                                .statusCode())
                .isEqualTo(202);
        JsonNode message = outbox(pending, "EMAIL_VERIFICATION");
        byte[] encrypted =
                jdbc.queryForObject(
                        "select encrypted_payload from identity.notification_outbox where"
                                + " recipient_id=?",
                        byte[].class,
                        pending.id());
        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(message.path("token").asText());
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/auth/email-verifications/confirm",
                                        message,
                                        guardian,
                                        null)
                                .statusCode())
                .isEqualTo(422);
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/auth/email-verifications/confirm",
                                        message,
                                        pending,
                                        null)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        json(send("GET", "/api/v1/me", null, pending, null))
                                .path("emailVerified")
                                .asBoolean())
                .isTrue();
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/auth/email-verifications/confirm",
                                        message,
                                        pending,
                                        null)
                                .statusCode())
                .isEqualTo(422);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from identity.audit_event where"
                                        + " action='EMAIL_VERIFIED'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void requestingAnotherEmailTokenInvalidatesThePreviousAndExpiryIsEnforced() throws Exception {
        User pending = seed("pending@example.test", "THERAPIST", false);
        send("POST", "/api/v1/auth/email-verifications", Map.of(), pending, null);
        JsonNode old = outbox(pending, "EMAIL_VERIFICATION");
        send("POST", "/api/v1/auth/email-verifications", Map.of(), pending, null);
        JsonNode current = outbox(pending, "EMAIL_VERIFICATION");
        assertThat(
                        send("POST", "/api/v1/auth/email-verifications/confirm", old, pending, null)
                                .statusCode())
                .isEqualTo(422);
        jdbc.update(
                "update identity.email_verification set expires_at=current_timestamp-interval '1"
                        + " second' where id=?",
                UUID.fromString(current.path("verificationId").asText()));
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/auth/email-verifications/confirm",
                                        current,
                                        pending,
                                        null)
                                .statusCode())
                .isEqualTo(422);
    }

    @Test
    void approvalRequiresAdminRecentPasswordAndEmailVerificationAndRevokesOldSessions()
            throws Exception {
        User pending = seed("pending@example.test", "THERAPIST", false);
        String path = "/api/v1/admin/members/" + pending.id() + "/approve";
        assertThat(send("POST", path, review(), guardian, key()).statusCode()).isEqualTo(403);
        assertThat(code(send("POST", path, review(), admin, key()))).isEqualTo("REAUTH_REQUIRED");
        reauth(admin);
        assertThat(code(send("POST", path, review(), admin, key())))
                .isEqualTo("MEMBER_STATE_CONFLICT");
        send("POST", "/api/v1/auth/email-verifications", Map.of(), pending, null);
        send(
                "POST",
                "/api/v1/auth/email-verifications/confirm",
                outbox(pending, "EMAIL_VERIFICATION"),
                pending,
                null);
        String idempotency = key();
        var accepted = send("POST", path, review(), admin, idempotency);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(json(accepted).path("role").asText()).isEqualTo("THERAPIST");
        assertThat(send("POST", path, review(), admin, idempotency).body())
                .isEqualTo(accepted.body());
        assertThat(send("GET", "/api/v1/me", null, pending, null).statusCode()).isEqualTo(401);
        User active = login(pending.id(), "pending@example.test");
        assertThat(send("GET", "/api/v1/children", null, active, null).statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from identity.audit_event where"
                                        + " action='MEMBER_APPROVE'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void adminCannotElevateAnAccountToAdminAndCannotSuspendItself() throws Exception {
        reauth(admin);
        var elevate = new HashMap<>(review());
        elevate.put("role", "ADMIN");
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/admin/members/" + guardian.id() + "/role",
                                        elevate,
                                        admin,
                                        key())
                                .statusCode())
                .isEqualTo(422);
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/admin/members/" + admin.id() + "/suspend",
                                        review(),
                                        admin,
                                        key())
                                .statusCode())
                .isEqualTo(403);
        assertThat(send("GET", "/api/v1/admin/members", null, guardian, null).statusCode())
                .isEqualTo(403);
        assertThat(send("GET", "/api/v1/admin/members?limit=1", null, admin, null).statusCode())
                .isEqualTo(200);
        assertThat(
                        json(send("GET", "/api/v1/admin/members?limit=1", null, admin, null))
                                .path("nextCursor")
                                .isNull())
                .isFalse();
    }

    @Test
    void rejectionAndRoleChangeAreAuditedAndGuardianWithChildrenCannotChangeRole()
            throws Exception {
        User pending = seed("pending@example.test", "GUARDIAN", false);
        reauth(admin);
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/admin/members/" + pending.id() + "/reject",
                                        review(),
                                        admin,
                                        key())
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        json(send(
                                        "GET",
                                        "/api/v1/me",
                                        null,
                                        login(pending.id(), "pending@example.test"),
                                        null))
                                .path("status")
                                .asText())
                .isEqualTo("REJECTED");
        child(guardian);
        var change = new HashMap<>(review());
        change.put("role", "THERAPIST");
        assertThat(
                        code(
                                send(
                                        "POST",
                                        "/api/v1/admin/members/" + guardian.id() + "/role",
                                        change,
                                        admin,
                                        key())))
                .isEqualTo("MEMBER_STATE_CONFLICT");
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/admin/members/" + otherGuardian.id() + "/role",
                                        change,
                                        admin,
                                        key())
                                .statusCode())
                .isEqualTo(200);
        assertThat(send("GET", "/api/v1/me", null, otherGuardian, null).statusCode())
                .isEqualTo(401);
    }

    @Test
    void bootstrapCreatesOnlyTheFirstPrivateAdministratorAndNeverElevatesPublicAccounts()
            throws Exception {
        jdbc.execute("truncate care.child,identity.member cascade");
        var bootstrap =
                new AdminBootstrap(
                        jdbc,
                        passwords,
                        manager,
                        guard,
                        audit,
                        clock,
                        "bootstrap@example.test",
                        PASSWORD);
        bootstrap.run(new DefaultApplicationArguments());
        bootstrap.run(new DefaultApplicationArguments());
        var user =
                login(
                        jdbc.queryForObject("select id from identity.member", UUID.class),
                        "bootstrap@example.test");
        assertThat(json(send("GET", "/api/v1/me", null, user, null)).path("role").asText())
                .isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("select count(*) from identity.member", Integer.class))
                .isEqualTo(1);
        var second =
                new AdminBootstrap(
                        jdbc,
                        passwords,
                        manager,
                        guard,
                        audit,
                        clock,
                        "another@example.test",
                        PASSWORD);
        assertThatThrownBy(() -> second.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void childrenAreOwnedByTheLoggedInGuardianAndRepeatedCreationIsIdempotent() throws Exception {
        String k = key();
        var first = send("POST", "/api/v1/children", childBody(), guardian, k);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(send("POST", "/api/v1/children", childBody(), guardian, k).body())
                .isEqualTo(first.body());
        UUID child = UUID.fromString(json(first).path("childId").asText());
        assertThat(
                        jdbc.queryForObject(
                                "select guardian_id from care.child where id=?", UUID.class, child))
                .isEqualTo(guardian.id());
        assertThat(
                        json(send("GET", "/api/v1/children", null, otherGuardian, null))
                                .path("items")
                                .size())
                .isZero();
        assertThat(send("GET", profilePath(child), null, otherGuardian, null).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", profilePath(child), null, admin, null).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/children", childBody(), therapist, key()).statusCode())
                .isEqualTo(403);
        var forged = new HashMap<>(childBody());
        forged.put("guardianId", otherGuardian.id());
        assertThat(send("POST", "/api/v1/children", forged, guardian, key()).statusCode())
                .isEqualTo(422);
    }

    @Test
    void concurrentChildCreationWithTheSameKeyHasOneEffect() throws Exception {
        String k = key();
        var responses =
                concurrent(
                        () -> send("POST", "/api/v1/children", childBody(), guardian, k),
                        () -> send("POST", "/api/v1/children", childBody(), guardian, k));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsOnly(201);
        assertThat(responses.getFirst().body()).isEqualTo(responses.getLast().body());
        assertThat(jdbc.queryForObject("select count(*) from care.child", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void profileValidationAndOptimisticRevisionAreEnforced() throws Exception {
        UUID child = child(guardian);
        var update =
                Map.of("expectedProfileVersion", 1, "pseudonym", "수정한 별명", "profile", profile());
        assertThat(
                        json(send("PUT", profilePath(child), update, guardian, key()))
                                .path("profileVersion")
                                .asLong())
                .isEqualTo(2);
        assertThat(code(send("PUT", profilePath(child), update, guardian, key())))
                .isEqualTo("RESOURCE_STATE_CONFLICT");
        var badProfile = new HashMap<>(profile());
        badProfile.put("communicationModes", List.of("SPEECH", "SPEECH"));
        var body = new HashMap<>(childBody());
        body.put("profile", badProfile);
        assertThat(send("POST", "/api/v1/children", body, guardian, key()).statusCode())
                .isEqualTo(422);
        body = new HashMap<>(childBody());
        body.put("guardianRelationshipConfirmed", false);
        assertThat(send("POST", "/api/v1/children", body, guardian, key()).statusCode())
                .isEqualTo(422);
    }

    @Test
    void consentsDefaultToDeniedArePurposeSpecificAndRequireBoundRecentProof() throws Exception {
        UUID child = child(guardian);
        assertThat(
                        json(send(
                                        "GET",
                                        "/api/v1/children/" + child + "/consents",
                                        null,
                                        guardian,
                                        null))
                                .path("items")
                                .size())
                .isZero();
        var forged = consentBody("CORE_PROFILE_LEARNING", true, UUID.randomUUID());
        assertThat(
                        code(
                                send(
                                        "POST",
                                        "/api/v1/children/" + child + "/consents",
                                        forged,
                                        guardian,
                                        key())))
                .isEqualTo("REAUTH_REQUIRED");
        UUID proof = reauth(guardian);
        assertThat(
                        code(
                                send(
                                        "POST",
                                        "/api/v1/children/" + child + "/consents",
                                        forged,
                                        guardian,
                                        key())))
                .isEqualTo("REAUTH_REQUIRED");
        var stale = new HashMap<>(consentBody("CORE_PROFILE_LEARNING", true, proof));
        stale.put("noticeVersion", "old");
        assertThat(
                        code(
                                send(
                                        "POST",
                                        "/api/v1/children/" + child + "/consents",
                                        stale,
                                        guardian,
                                        key())))
                .isEqualTo("CONSENT_NOTICE_CHANGED");
        consent(child, "CORE_PROFILE_LEARNING", true, proof);
        var items =
                json(send("GET", "/api/v1/children/" + child + "/consents", null, guardian, null))
                        .path("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("scope").asText()).isEqualTo("CORE_PROFILE_LEARNING");
        assertThat(
                        send(
                                        "GET",
                                        "/api/v1/children/" + child + "/consents",
                                        null,
                                        otherGuardian,
                                        null)
                                .statusCode())
                .isEqualTo(404);
        assertThat(json(send("GET", "/api/v1/consent-notices", null, guardian, null)).size())
                .isEqualTo(6);
    }

    @Test
    void therapistInvitationRequiresConsentAndRecentGuardianAuthentication() throws Exception {
        UUID child = child(guardian);
        assertThat(code(invite(child, "therapist@example.test"))).isEqualTo("REAUTH_REQUIRED");
        reauth(guardian);
        assertThat(code(invite(child, "therapist@example.test"))).isEqualTo("CONSENT_REQUIRED");
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/children/" + child + "/therapist-invitations",
                                        Map.of("therapistEmail", "therapist@example.test"),
                                        otherGuardian,
                                        key())
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void mutualAcceptanceGrantsOnlyTheRequestedChildAndReadCapabilities() throws Exception {
        UUID child = readyChild();
        UUID invitation = inviteId(child, "therapist@example.test");
        var inbox = send("GET", "/api/v1/me/therapist-invitations", null, therapist, null);
        assertThat(inbox.body())
                .doesNotContain(child.toString(), "pseudonym", "profile", "SYNTHETIC_CHILD");
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(404);
        assertThat(
                        send(
                                        "POST",
                                        acceptPath(invitation),
                                        Map.of("treatmentRelationshipConfirmed", true),
                                        otherTherapist,
                                        key())
                                .statusCode())
                .isEqualTo(403);
        reauth(otherTherapist);
        assertThat(
                        send(
                                        "POST",
                                        acceptPath(invitation),
                                        Map.of("treatmentRelationshipConfirmed", true),
                                        otherTherapist,
                                        key())
                                .statusCode())
                .isEqualTo(404);
        reauth(therapist);
        accept(invitation, key());
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(200);
        assertThat(
                        json(send("GET", "/api/v1/children", null, therapist, null))
                                .path("items")
                                .size())
                .isEqualTo(1);
        UUID otherChild = child(otherGuardian);
        assertThat(send("GET", profilePath(otherChild), null, therapist, null).statusCode())
                .isEqualTo(404);
        assertThat(
                        send(
                                        "GET",
                                        "/api/v1/children/" + child + "/consents",
                                        null,
                                        therapist,
                                        null)
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        send(
                                        "PUT",
                                        profilePath(child),
                                        Map.of(
                                                "expectedProfileVersion",
                                                1,
                                                "pseudonym",
                                                "x",
                                                "profile",
                                                profile()),
                                        therapist,
                                        key())
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void genericInvitationDoesNotRevealEmailMembershipOrAutoAttachFutureAccounts()
            throws Exception {
        UUID child = readyChild();
        var valid = invite(child, "therapist@example.test");
        var absent = invite(child, "future@example.test");
        assertThat(absent.statusCode()).isEqualTo(202);
        assertThat(absent.body()).isEqualTo(valid.body());
        assertThat(
                        jdbc.queryForObject(
                                "select therapist_id is null from care.therapist_invitation where"
                                        + " target_email='future@example.test'",
                                Boolean.class))
                .isTrue();
        User future = seed("future@example.test", "THERAPIST", true);
        assertThat(
                        json(send("GET", "/api/v1/me/therapist-invitations", null, future, null))
                                .path("items")
                                .size())
                .isZero();
        assertThat(send("GET", profilePath(child), null, future, null).statusCode()).isEqualTo(404);
    }

    @Test
    void revokedConnectionCannotBeRestoredByAcceptReplayOrReusingOldInvitation() throws Exception {
        UUID child = readyChild();
        UUID invitation = inviteId(child, "therapist@example.test");
        reauth(therapist);
        String acceptKey = key();
        accept(invitation, acceptKey);
        assertThat(
                        send(
                                        "DELETE",
                                        "/api/v1/children/"
                                                + child
                                                + "/therapists/"
                                                + therapist.id(),
                                        null,
                                        guardian,
                                        key())
                                .statusCode())
                .isEqualTo(204);
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(404);
        assertThat(
                        send(
                                        "POST",
                                        acceptPath(invitation),
                                        Map.of("treatmentRelationshipConfirmed", true),
                                        therapist,
                                        acceptKey)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        code(
                                send(
                                        "POST",
                                        acceptPath(invitation),
                                        Map.of("treatmentRelationshipConfirmed", true),
                                        therapist,
                                        key())))
                .isEqualTo("RESOURCE_NOT_FOUND");
        UUID fresh = inviteId(child, "therapist@example.test");
        assertThat(fresh).isNotEqualTo(invitation);
        accept(fresh, key());
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void consentWithdrawalRevokesConnectionAndPendingInvitationsAndRegrantDoesNotRestoreThem()
            throws Exception {
        UUID child = readyChild();
        UUID id = inviteId(child, "therapist@example.test");
        reauth(therapist);
        accept(id, key());
        inviteId(child, "other-therapist@example.test");
        UUID proof = reauth(guardian);
        consent(child, "CORE_PROFILE_LEARNING", false, proof);
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from care.therapist_invitation where"
                                        + " state='PENDING'",
                                Integer.class))
                .isZero();
        consent(child, "CORE_PROFILE_LEARNING", true, proof);
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", profilePath(child), null, guardian, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void expiredAndCancelledInvitationsCannotBeAccepted() throws Exception {
        UUID child = readyChild();
        UUID id = inviteId(child, "therapist@example.test");
        reauth(therapist);
        jdbc.update(
                "update care.therapist_invitation set expires_at=current_timestamp-interval '1"
                        + " second' where id=?",
                id);
        assertThat(
                        code(
                                send(
                                        "POST",
                                        acceptPath(id),
                                        Map.of("treatmentRelationshipConfirmed", true),
                                        therapist,
                                        key())))
                .isEqualTo("INVITATION_STATE_CONFLICT");
        UUID next = inviteId(child, "therapist@example.test");
        assertThat(
                        send(
                                        "DELETE",
                                        "/api/v1/children/"
                                                + child
                                                + "/therapist-invitations/"
                                                + next,
                                        null,
                                        guardian,
                                        key())
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        send(
                                        "POST",
                                        acceptPath(next),
                                        Map.of("treatmentRelationshipConfirmed", true),
                                        therapist,
                                        key())
                                .statusCode())
                .isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from care.child_therapist", Integer.class))
                .isZero();
    }

    @Test
    void concurrentAcceptanceAndCancellationHaveExactlyOneWinner() throws Exception {
        UUID child = readyChild();
        UUID id = inviteId(child, "therapist@example.test");
        reauth(therapist);
        var responses =
                concurrent(
                        () ->
                                send(
                                        "POST",
                                        acceptPath(id),
                                        Map.of("treatmentRelationshipConfirmed", true),
                                        therapist,
                                        key()),
                        () ->
                                send(
                                        "DELETE",
                                        "/api/v1/children/"
                                                + child
                                                + "/therapist-invitations/"
                                                + id,
                                        null,
                                        guardian,
                                        key()));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(204, 409);
        String state =
                jdbc.queryForObject(
                        "select state from care.therapist_invitation where id=?", String.class, id);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from care.child_therapist where state='ACTIVE'",
                                Integer.class))
                .isEqualTo(state.equals("ACCEPTED") ? 1 : 0);
    }

    @Test
    void therapistCanEndOwnConnectionButCannotEndAnotherTherapistsConnection() throws Exception {
        UUID child = readyChild();
        UUID id = inviteId(child, "therapist@example.test");
        reauth(therapist);
        accept(id, key());
        assertThat(
                        send(
                                        "DELETE",
                                        "/api/v1/children/"
                                                + child
                                                + "/therapists/"
                                                + therapist.id(),
                                        null,
                                        otherTherapist,
                                        key())
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        send(
                                        "DELETE",
                                        "/api/v1/children/"
                                                + child
                                                + "/therapists/"
                                                + therapist.id(),
                                        null,
                                        therapist,
                                        key())
                                .statusCode())
                .isEqualTo(204);
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void guardianSuspensionRevokesSessionsAndConnectionsAndBlocksClinicalAccess() throws Exception {
        UUID child = readyChild();
        UUID id = inviteId(child, "therapist@example.test");
        reauth(therapist);
        accept(id, key());
        reauth(admin);
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/admin/members/" + guardian.id() + "/suspend",
                                        review(),
                                        admin,
                                        key())
                                .statusCode())
                .isEqualTo(200);
        assertThat(send("GET", profilePath(child), null, guardian, null).statusCode())
                .isEqualTo(401);
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(404);
        assertThat(jdbc.queryForObject("select state from care.child_therapist", String.class))
                .isEqualTo("REVOKED");
    }

    @Test
    void deletingAChildRevokesConnectionsAndCannotLeakAnIdempotentProfileReplay() throws Exception {
        UUID child = readyChild();
        UUID id = inviteId(child, "therapist@example.test");
        reauth(therapist);
        accept(id, key());
        var update = Map.of("expectedProfileVersion", 1, "pseudonym", "new", "profile", profile());
        String k = key();
        assertThat(send("PUT", profilePath(child), update, guardian, k).statusCode())
                .isEqualTo(200);
        assertThat(send("DELETE", "/api/v1/children/" + child, null, guardian, key()).statusCode())
                .isEqualTo(204);
        assertThat(send("PUT", profilePath(child), update, guardian, k).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void expiredReauthenticationAndAnotherSessionsProofAreRejected() throws Exception {
        UUID child = child(guardian);
        UUID proof = reauth(guardian);
        jdbc.update(
                "update identity.auth_session set reauthenticated_at=current_timestamp-interval '6"
                        + " minutes' where member_id=?",
                guardian.id());
        assertThat(
                        code(
                                send(
                                        "POST",
                                        "/api/v1/children/" + child + "/consents",
                                        consentBody("CORE_PROFILE_LEARNING", true, proof),
                                        guardian,
                                        key())))
                .isEqualTo("REAUTH_REQUIRED");
        User sameMember = login(guardian.id(), "guardian@example.test");
        reauth(sameMember);
        assertThat(
                        code(
                                send(
                                        "POST",
                                        "/api/v1/children/" + child + "/consents",
                                        consentBody("CORE_PROFILE_LEARNING", true, proof),
                                        sameMember,
                                        key())))
                .isEqualTo("REAUTH_REQUIRED");
    }

    @Test
    void optionalConsentWithdrawalDoesNotRevokeCoreAccess() throws Exception {
        UUID child = readyChild();
        UUID invitation = inviteId(child, "therapist@example.test");
        reauth(therapist);
        accept(invitation, key());
        consent(child, "SPEECH_STT", true, reauth(guardian));
        consent(child, "SPEECH_STT", false, reauth(guardian));
        assertThat(send("GET", profilePath(child), null, therapist, null).statusCode())
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "select granted from care.consent where child_id=? and"
                                        + " scope='SPEECH_STT'",
                                Boolean.class,
                                child))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select granted from care.consent where child_id=? and"
                                        + " scope='CORE_PROFILE_LEARNING'",
                                Boolean.class,
                                child))
                .isTrue();
    }

    @Test
    void otherGuardiansCannotConsumeTheChildsInvitationRateBudget() throws Exception {
        UUID child = readyChild();
        assertThat(
                        send(
                                        "POST",
                                        "/api/v1/children/" + child + "/therapist-invitations",
                                        Map.of("therapistEmail", "therapist@example.test"),
                                        otherGuardian,
                                        key())
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from identity.auth_rate_bucket where bucket_key=?",
                                Integer.class,
                                com.planb.emotionfriend.api.auth.TokenSecrets.hash(
                                        "invite-child:" + child)))
                .isZero();
        assertThat(invite(child, "therapist@example.test").statusCode()).isEqualTo(202);
    }

    @Test
    void swaggerDocumentsActualStatusCodesAndChildResponseFields() throws Exception {
        var api = json(send("GET", "/v3/api-docs", null, null, null));
        var paths = api.path("paths");
        assertThat(paths.path("/api/v1/children").path("post").path("responses").has("201"))
                .isTrue();
        assertThat(
                        paths.path("/api/v1/children/{childId}/therapist-invitations")
                                .path("post")
                                .path("responses")
                                .has("202"))
                .isTrue();
        assertThat(
                        paths.path("/api/v1/therapist-invitations/{invitationId}/accept")
                                .path("post")
                                .path("responses")
                                .has("204"))
                .isTrue();
        assertThat(paths.path("/api/v1/auth/logout").path("post").path("responses").has("204"))
                .isTrue();
        assertThat(
                        api.path("components")
                                .path("schemas")
                                .path("ProfileVersion")
                                .path("properties")
                                .has("childId"))
                .isTrue();
        assertThat(
                        paths.path("/api/v1/children/{childId}/profile")
                                .path("get")
                                .path("security")
                                .toString())
                .contains("adultBearer")
                .doesNotContain("childBearer");
    }

    @Test
    void simultaneousInvitationRetriesDoNotExhaustTheConnectionPoolOrDuplicateEffects()
            throws Exception {
        UUID child = readyChild();
        String retryKey = key();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var work = new ArrayList<Callable<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++)
                work.add(
                        () ->
                                send(
                                        "POST",
                                        "/api/v1/children/" + child + "/therapist-invitations",
                                        Map.of("therapistEmail", "therapist@example.test"),
                                        guardian,
                                        retryKey));
            for (var result : pool.invokeAll(work))
                assertThat(result.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from care.therapist_invitation", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from identity.notification_outbox where"
                                        + " kind='THERAPIST_INVITATION'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void deletedChildCannotBeRetrievedByReplayingItsOriginalRegistration() throws Exception {
        String registrationKey = key();
        var created = send("POST", "/api/v1/children", childBody(), guardian, registrationKey);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID child = UUID.fromString(json(created).path("childId").asText());
        reauth(guardian);
        assertThat(send("DELETE", "/api/v1/children/" + child, null, guardian, key()).statusCode())
                .isEqualTo(204);
        assertThat(
                        send("POST", "/api/v1/children", childBody(), guardian, registrationKey)
                                .statusCode())
                .isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from care.child", Integer.class))
                .isEqualTo(1);
    }

    private User seed(String email, String role, boolean active) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into"
                    + " identity.member(id,email,display_name,password_hash,requested_role,role,status,email_verified_at,created_at,updated_at)"
                    + " values(?,?,?,?,?,?,?,?,current_timestamp,current_timestamp)",
                id,
                email,
                "합성 사용자",
                hash,
                role.equals("ADMIN") ? null : role,
                active ? role : null,
                active ? "ACTIVE" : "PENDING",
                active ? Timestamp.from(clock.instant()) : null);
        return login(id, email);
    }

    private User login(UUID id, String email) throws Exception {
        var response =
                send(
                        "POST",
                        "/api/v1/auth/login",
                        Map.of("loginId", email, "password", PASSWORD),
                        null,
                        null);
        assertThat(response.statusCode()).isEqualTo(200);
        return new User(id, json(response).path("credential").path("accessToken").asText());
    }

    private UUID reauth(User user) throws Exception {
        var response =
                send(
                        "POST",
                        "/api/v1/auth/reauthenticate",
                        Map.of("password", PASSWORD),
                        user,
                        null);
        assertThat(response.statusCode()).isEqualTo(200);
        return UUID.fromString(json(response).path("verificationRef").asText());
    }

    private Map<String, Object> profile() {
        return Map.of(
                "schemaVersion",
                "0.3.0",
                "gender",
                "BOY",
                "ageBand",
                "TARGET_8_TO_13",
                "primaryLanguage",
                "ko",
                "receptiveSupport",
                "UNKNOWN",
                "recognitionSupport",
                "UNKNOWN",
                "expressiveSupport",
                "UNKNOWN",
                "communicationModes",
                List.of("UNKNOWN"),
                "supportPreferences",
                List.of("UNKNOWN"));
    }

    private Map<String, Object> childBody() {
        return Map.of(
                "schemaVersion",
                "0.3.0",
                "pseudonym",
                "SYNTHETIC_CHILD",
                "profile",
                profile(),
                "guardianRelationshipConfirmed",
                true);
    }

    private UUID child(User owner) throws Exception {
        var response = send("POST", "/api/v1/children", childBody(), owner, key());
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(json(response).path("childId").asText());
    }

    private UUID readyChild() throws Exception {
        UUID id = child(guardian);
        consent(id, "CORE_PROFILE_LEARNING", true, reauth(guardian));
        return id;
    }

    private Map<String, Object> consentBody(String scope, boolean granted, UUID proof) {
        return Map.of(
                "schemaVersion",
                "0.3.0",
                "scope",
                scope,
                "granted",
                granted,
                "noticeVersion",
                ChildService.NOTICE_VERSION,
                "guardianVerificationRef",
                proof);
    }

    private void consent(UUID child, String scope, boolean value, UUID proof) throws Exception {
        var response =
                send(
                        "POST",
                        "/api/v1/children/" + child + "/consents",
                        consentBody(scope, value, proof),
                        guardian,
                        key());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> invite(UUID child, String email) throws Exception {
        return send(
                "POST",
                "/api/v1/children/" + child + "/therapist-invitations",
                Map.of("therapistEmail", email),
                guardian,
                key());
    }

    private UUID inviteId(UUID child, String email) throws Exception {
        assertThat(invite(child, email).statusCode()).isEqualTo(202);
        return jdbc.queryForObject(
                "select id from care.therapist_invitation where child_id=? and target_email=? and"
                        + " state='PENDING'",
                UUID.class,
                child,
                email);
    }

    private void accept(UUID id, String key) throws Exception {
        assertThat(
                        send(
                                        "POST",
                                        acceptPath(id),
                                        Map.of("treatmentRelationshipConfirmed", true),
                                        therapist,
                                        key)
                                .statusCode())
                .isEqualTo(204);
    }

    private String profilePath(UUID child) {
        return "/api/v1/children/" + child + "/profile";
    }

    private String acceptPath(UUID id) {
        return "/api/v1/therapist-invitations/" + id + "/accept";
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private Map<String, Object> review() {
        return Map.of("reason", "합성 테스트 검토", "reviewReference", "synthetic-review-1");
    }

    private String code(HttpResponse<String> response) {
        return json(response).path("code").asText();
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private JsonNode outbox(User user, String kind) {
        return jdbc.query(
                        "select id,encrypted_payload from identity.notification_outbox where"
                                + " recipient_id=? and kind=? order by created_at desc limit 1",
                        (rs, row) ->
                                mapper.readTree(
                                        cipher.decrypt(
                                                rs.getBytes(2),
                                                "notification:"
                                                        + rs.getObject(1, UUID.class)
                                                        + ":"
                                                        + user.id()
                                                        + ":"
                                                        + kind)),
                        user.id(),
                        kind)
                .getFirst();
    }

    private HttpResponse<String> send(
            String method, String path, Object body, User user, String key) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(20));
        if (user != null) builder.header("Authorization", "Bearer " + user.access());
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<HttpResponse<String>> concurrent(
            Callable<HttpResponse<String>> one, Callable<HttpResponse<String>> two)
            throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(one);
            var b = pool.submit(two);
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private record User(UUID id, String access) {
        @Override
        public String toString() {
            return "User[redacted]";
        }
    }
}
