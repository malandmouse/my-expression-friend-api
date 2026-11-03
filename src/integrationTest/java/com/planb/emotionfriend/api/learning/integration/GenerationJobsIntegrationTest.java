package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.GenerationJobs.*;
import static org.assertj.core.api.Assertions.*;

import com.planb.emotionfriend.api.common.FoundationIntegrationTest;
import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import com.planb.emotionfriend.api.common.idempotency.IdempotencyService;
import com.planb.emotionfriend.api.common.idempotency.StoredResponse;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.learning.LearningContracts.Receipt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerationJobsIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired IdempotencyService httpReplay;
    @Autowired JsonMapper json;

    private final TestClock clock = new TestClock();
    private final UUID child = UUID.randomUUID();
    private final UUID adult = UUID.randomUUID();
    private final Scope authoring = new Scope(child, adult, Kind.AUTHORING);
    private final AtomicInteger calls = new AtomicInteger();
    private Repository repository;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        FoundationIntegrationTest.isolatedPostgres(registry);
    }

    @BeforeEach void setUp() {
        repository = new PostgresGenerationJobs(db, transactions, clock);
        seedIdentity(db);
    }

    private void seedIdentity(JdbcTemplate db) {
        db.update("""
                insert into identity.member(id,email,display_name,password_hash,requested_role,role,status,
                                            email_verified_at,created_at,updated_at)
                values (?,?,'SYNTHETIC','unused','GUARDIAN','GUARDIAN','ACTIVE',now(),now(),now())
                """, adult, adult + "@example.test");
        db.update("""
                insert into care.child(id,guardian_id,pseudonym,profile,profile_version,created_at,updated_at)
                values (?,?,'SYNTHETIC','{}',1,now(),now())
                """, child, adult);
    }

    @Test void existingHttpIdempotencyAndBusinessReservationHaveDifferentScopesAndCommitTogether() {
        var jobs = authoring(this::success);
        var request = request();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        var initial = submit(jobs, request, first, 1);
        assertThat(submit(jobs, request, first, 1).body()).containsExactly(initial.body());
        assertThat(submit(jobs, request, second, 1).body()).containsExactly(initial.body());
        assertThatThrownBy(() -> submit(jobs, request, first, 2))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        UUID job = UUID.fromString(json.readTree(initial.body()).path("jobId").asText());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> jobs.dispatch(job)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            submit(jobs, request(), UUID.randomUUID().toString(), 1);
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jobCount()).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from foundation.idempotency_record where path=?", Integer.class,
                "/synthetic-job-reservation/" + child)).isEqualTo(2);
        assertThat(calls).hasValue(0);
        assertThat(jobs.dispatch(job)).isTrue();
        assertThat(jobs.dispatch(job)).isFalse();
        assertThat(calls).hasValue(1);

        db.update("update care.child set deleted_at=now() where id=?", child);
        assertThatThrownBy(() -> submit(jobs, request, first, 1)).isInstanceOf(PostgresGenerationJobs.Conflict.class);
        assertThat(jobs.status(job, new Scope(child, UUID.randomUUID(), Kind.AUTHORING))).isEmpty();
        assertThat(jobs.status(job, new Scope(UUID.randomUUID(), adult, Kind.AUTHORING))).isEmpty();
    }

    @Test void concurrentReservationAndClaimOnlyInvokeOneWorker() throws Exception {
        var jobs = authoring(this::success);
        var request = request();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var one = executor.submit(() -> { await(start); return jobs.reserve(request, deadline()); });
            var two = executor.submit(() -> { await(start); return jobs.reserve(request, deadline()); });
            start.countDown();
            Job first = one.get(10, TimeUnit.SECONDS);
            assertThat(two.get(10, TimeUnit.SECONDS).id()).isEqualTo(first.id());
            var dispatch = new CountDownLatch(1);
            var claimOne = executor.submit(() -> { await(dispatch); return jobs.dispatch(first.id()); });
            var claimTwo = executor.submit(() -> { await(dispatch); return jobs.dispatch(first.id()); });
            dispatch.countDown();
            assertThat(List.of(claimOne.get(10, TimeUnit.SECONDS), claimTwo.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jobs.status(first.id(), authoring).orElseThrow().state()).isEqualTo(State.RESULT_READY);
        }
        assertThat(calls).hasValue(1);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test void staleCancelVersionAndLateResultCannotReplaceCancellation() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var jobs = authoring(claim -> {
            Result result = success(claim);
            started.countDown();
            await(finish);
            return result;
        });
        Request request = request();
        Job pending = jobs.reserve(request, deadline());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = executor.submit(() -> jobs.dispatch(pending.id()));
            try {
                await(started);
                Job running = jobs.status(pending.id(), authoring).orElseThrow();
                assertThat(running.state()).isEqualTo(State.RUNNING);
                assertThatThrownBy(() -> jobs.cancel(pending.id(), authoring, pending.version()))
                        .isInstanceOf(PostgresGenerationJobs.Conflict.class);
                Job cancelled = jobs.cancel(pending.id(), authoring, running.version());
                assertThat(cancelled.state()).isEqualTo(State.CANCELLED);
                assertThat(jobs.cancel(pending.id(), authoring, running.version())).isEqualTo(cancelled);
            } finally {
                finish.countDown();
            }
            assertThat(worker.get(10, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(jobs.status(pending.id(), authoring).orElseThrow().artifact()).isNull();
        clock.now = clock.instant().plusSeconds(120);
        assertThat(jobs.reserve(request, deadline()).id()).isEqualTo(pending.id());
        assertThat(jobs.dispatch(pending.id())).isFalse();
    }

    @Test void revokedAndChangedInputsRejectResultsAndRevocationBeforeClaimSkipsWorker() {
        for (String update : List.of("profile_version=profile_version+1", "deleted_at=now()")) {
            db.update("update care.child set profile_version=1,deleted_at=null where id=?", child);
            var jobs = authoring(claim -> {
                // This write needs the same row the guard locks. It must finish during the worker call.
                db.update("update care.child set " + update + " where id=?", child);
                return success(claim);
            });
            var job = jobs.reserve(request(), deadline());
            assertThat(jobs.dispatch(job.id())).isFalse();
            assertThat(rawState(job)).isEqualTo("CANCELLED");
            assertThatThrownBy(() -> jobs.status(job.id(), authoring)).isInstanceOf(PostgresGenerationJobs.Conflict.class);
        }
        db.update("update care.child set profile_version=1,deleted_at=null where id=?", child);
        var jobs = authoring(this::success);
        var blocked = jobs.reserve(request(), deadline());
        db.update("update care.child set deleted_at=now() where id=?", child);
        assertThat(jobs.dispatch(blocked.id())).isFalse();
        assertThat(rawState(blocked)).isEqualTo("CANCELLED");
        assertThat(calls).hasValue(2);
    }

    @Test void timeoutAndExpiredClaimRemainUnknownWithoutRedispatchAfterRepositoryRecreation() {
        var jobs = authoring(claim -> {
            calls.incrementAndGet();
            throw new IllegalStateException("synthetic transport timeout after send");
        });
        var timeoutRequest = request();
        var timedOut = jobs.reserve(timeoutRequest, deadline());
        assertThat(jobs.dispatch(timedOut.id())).isTrue();
        assertThat(rawState(timedOut)).isEqualTo("OUTCOME_UNKNOWN");

        var expired = jobs.reserve(request(), deadline());
        Job claim = repository.claim(expired.id(), Kind.AUTHORING, this::access).orElseThrow();
        var neverSent = jobs.reserve(request(), deadline());
        clock.now = clock.instant().plusSeconds(120);
        repository = new PostgresGenerationJobs(db, transactions, clock);
        var restarted = authoring(this::success);
        assertThat(restarted.status(expired.id(), authoring).orElseThrow().state()).isEqualTo(State.OUTCOME_UNKNOWN);
        assertThat(restarted.status(neverSent.id(), authoring).orElseThrow().state()).isEqualTo(State.FAILED);
        assertThat(repository.settle(claim, new Result(State.HELD, null), this::access)).isFalse();
        assertThat(restarted.reserve(timeoutRequest, deadline()).id()).isEqualTo(timedOut.id());
        assertThat(restarted.dispatch(timedOut.id())).isFalse();
        assertThat(restarted.dispatch(expired.id())).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test void aDefiniteHoldAndFailureAreDistinctFromUnknown() {
        for (State state : List.of(State.HELD, State.FAILED)) {
            var jobs = authoring(claim -> new Result(state, null));
            var pending = jobs.reserve(request(), deadline());
            assertThat(jobs.dispatch(pending.id())).isTrue();
            assertThat(jobs.status(pending.id(), authoring).orElseThrow().state()).isEqualTo(state);
            assertThat(jobs.dispatch(pending.id())).isFalse();
        }
    }

    @Test void committedReceiptIsRequiredAndReactionFailureOrUnknownDoesNotTouchCompletion() {
        var scope = new Scope(child, adult, Kind.REACTION_MEDIA);
        var settings = UUID.randomUUID();
        var reaction = new ReactionJobs.ReviewedReaction(UUID.randomUUID(), "고마워요.",
                ProductHashes.textHash("고마워요."), "npc-reviewed", settings);
        for (State state : List.of(State.FAILED, State.OUTCOME_UNKNOWN)) {
            var receipt = syntheticReceipt();
            var jobs = new ReactionJobs(repository, (actual, id) -> {
                if (!actual.equals(scope) || !id.equals(receipt.receiptId())) return Optional.empty();
                boolean committed = Boolean.TRUE.equals(db.queryForObject(
                        "select exists(select 1 from learning.receipt where id=?)", Boolean.class, id));
                return committed ? Optional.of(new ReactionJobs.Accepted(receipt, 1, 1, reaction)) : Optional.empty();
            }, (claim, approved) -> {
                assertThat(approved).isEqualTo(reaction);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                calls.incrementAndGet();
                return new Result(state, null);
            });
            assertThatThrownBy(() -> jobs.reserve(scope, receipt.receiptId(), deadline())).isInstanceOf(IllegalArgumentException.class);
            var noReaction = new ReactionJobs(repository, (actual, id) -> Optional.of(new ReactionJobs.Accepted(receipt, 1, 1, null)),
                    (claim, approved) -> { throw new AssertionError("No voice is available"); });
            assertThatThrownBy(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(tx -> jobs.reserve(scope, receipt.receiptId(), deadline())))
                    .isInstanceOf(IllegalStateException.class);
            new TransactionTemplate(transactions).executeWithoutResult(tx -> seedCompletion(db, receipt));
            var before = completionSnapshot(receipt);
            assertThat(noReaction.reserve(scope, receipt.receiptId(), deadline()).availability())
                    .isEqualTo(ReactionJobs.Availability.NOT_AVAILABLE);
            var reserved = jobs.reserve(scope, receipt.receiptId(), deadline()).job();
            assertThat(jobs.reserve(scope, receipt.receiptId(), deadline()).job().id()).isEqualTo(reserved.id());
            assertThat(jobs.dispatch(reserved.id())).isTrue();
            assertThat(jobs.dispatch(reserved.id())).isFalse();
            assertThat(jobs.status(scope, reserved.id()).orElseThrow().state()).isEqualTo(state);
            assertThat(completionSnapshot(receipt)).isEqualTo(before);
            clock.now = clock.instant().plusSeconds(120);
            assertThat(jobs.reserve(scope, receipt.receiptId(), deadline()).job().id()).isEqualTo(reserved.id());
        }
        assertThat(calls).hasValue(2);
        assertThat(jobCount()).isEqualTo(2);
    }

    @Test void reactionCannotDispatchWhenAnAdapterReturnsADifferentBindingForTheSameReceipt() {
        var scope = new Scope(child, adult, Kind.REACTION_MEDIA);
        var receipt = syntheticReceipt();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> seedCompletion(db, receipt));
        var settings = UUID.randomUUID();
        var ref = UUID.randomUUID();
        var original = new ReactionJobs.ReviewedReaction(ref, "고마워요.", ProductHashes.textHash("고마워요."), "npc", settings);
        var current = new java.util.concurrent.atomic.AtomicReference<>(original);
        var jobs = new ReactionJobs(repository, (actual, id) ->
                Optional.of(new ReactionJobs.Accepted(receipt, 1, 1, current.get())),
                (claim, reaction) -> { throw new AssertionError("A different binding cannot dispatch"); });
        Job reserved = jobs.reserve(scope, receipt.receiptId(), deadline()).job();
        current.set(new ReactionJobs.ReviewedReaction(ref, "고마워요.", original.textHash(), "other-npc", settings));
        assertThat(jobs.dispatch(reserved.id())).isFalse();
        assertThat(rawState(reserved)).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> jobs.reserve(scope, receipt.receiptId(), deadline()))
                .isInstanceOf(PostgresGenerationJobs.Conflict.class);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test void v9IsAdditiveAndLeavesV8IdentitiesCompletionAndEncryptedReplayIntact() {
        // The shared integration bootstrap already restricts EF_TEST_DB_URL to the loopback test cluster.
        String url = System.getenv("EF_TEST_DB_URL");
        String password = System.getenv().getOrDefault("EF_TEST_DB_PASSWORD", "");
        String database = "generation_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("create database " + database);
        try {
            String isolated = url.substring(0, url.lastIndexOf('/') + 1) + database;
            var previous = new JdbcTemplate(new DriverManagerDataSource(isolated, "foundation_test", password));
            Flyway.configure().dataSource(isolated, "foundation_test", password).target("8").load().migrate();
            seedIdentity(previous);
            Receipt receipt = syntheticReceipt();
            seedCompletion(previous, receipt);
            previous.update("""
                    insert into learning.story_event(receipt_id,child_id,world_revision,effects,recorded_at)
                    values (?,?,1,'{"npc_helped":true}',now())
                    """, receipt.receiptId(), child);
            previous.update("insert into learning.child_world(child_id,revision) values(?,1)", child);
            previous.update("""
                    insert into foundation.idempotency_record
                      (actor_scope,method,path,key_digest,request_digest,status_code,content_type,encrypted_body)
                    values ('synthetic','POST','/synthetic',?,?,200,'application/json',?)
                    """, "c".repeat(64), "d".repeat(64), new byte[32]);
            var before = persistedSnapshots(previous);

            var upgrade = Flyway.configure().dataSource(isolated, "foundation_test", password).target("9").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(persistedSnapshots(previous)).isEqualTo(before);
            assertThat(previous.queryForObject("select count(*) from learning.generation_job", Integer.class)).isZero();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        } finally {
            db.execute("drop database " + database);
        }
    }

    private static Map<String, List<String>> persistedSnapshots(JdbcTemplate db) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("identity.member", "care.child", "learning.material", "learning.plan",
                "learning.plan_slot", "learning.session", "learning.receipt", "learning.star_award",
                "learning.story_event", "learning.child_world",
                "foundation.idempotency_record")) {
            result.put(table, db.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return result;
    }

    private GenerationJobs authoring(Worker worker) {
        return new GenerationJobs(Kind.AUTHORING, repository,
                (request, now) -> new Fingerprint(ProductHashes.textHash(request.input().sourceRef().toString()),
                        now.plusSeconds(60)), this::access, worker);
    }

    private Access access(Request request) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        return db.queryForObject("""
                select profile_version, deleted_at is not null as revoked from care.child
                where id=? and guardian_id=? for share
                """, (rs, row) -> rs.getBoolean("revoked") ? Access.REVOKED
                : rs.getLong("profile_version") == request.input().inputRevision() ? Access.CURRENT : Access.STALE,
                request.scope().childId(), request.scope().requesterId());
    }

    private StoredResponse submit(GenerationJobs jobs, Request request, String key, int body) {
        var actor = new ActorPrincipal(adult.toString(), ActorPrincipal.Kind.ADULT, null, null, null);
        return httpReplay.execute(actor, "POST", "/synthetic-job-reservation/" + child, key, Map.of("body", body), () -> {
            if (access(request) != Access.CURRENT) throw new PostgresGenerationJobs.Conflict("Access revoked");
        }, () -> StoredResponse.json(json, 202, Map.of("jobId", jobs.reserve(request, deadline()).id())));
    }

    private Result success(Job claim) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(rawState(claim)).isEqualTo("RUNNING");
        calls.incrementAndGet();
        return new Result(State.RESULT_READY, new Artifact(UUID.randomUUID(), ProductHashes.bytesHash(new byte[]{1, 2})));
    }

    private int jobCount() {
        return db.queryForObject("select count(*) from learning.generation_job where child_id=?", Integer.class, child);
    }

    private String rawState(Job job) {
        return db.queryForObject("select state from learning.generation_job where id=?", String.class, job.id());
    }

    private Request request() {
        return new Request(authoring, new Input(UUID.randomUUID(), 1, 1, UUID.randomUUID(), null));
    }

    private Instant deadline() { return clock.instant().plusSeconds(30); }

    private Receipt syntheticReceipt() {
        return new Receipt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, clock.instant(),
                1, 1, 1, 1, UUID.randomUUID(), 1, "ORIGINAL", adult.toString(), "SYNTHETIC");
    }

    private void seedCompletion(JdbcTemplate db, Receipt receipt) {
        var material = UUID.randomUUID();
        db.update("""
                insert into learning.material(id,child_id,summary_id,scenario_version_id,subject,summary,content_hash,
                                              review_reference,content,created_at)
                values (?,?,?,?,'SYNTHETIC','SYNTHETIC',?,'synthetic-review','{}',now())
                """, material, child, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));
        db.update("""
                insert into learning.plan(id,child_id,day_index,timezone,publication_reference,published_at)
                values (?,?,(select coalesce(max(day_index),0)+1 from learning.plan where child_id=?),
                        'Asia/Seoul','synthetic-publication',now())
                """, receipt.planId(), child, child);
        db.update("insert into learning.plan_slot(plan_id,slot,material_id,content_hash) values(?,1,?,?)",
                receipt.planId(), material, "a".repeat(64));
        db.update("""
                insert into learning.session(id,plan_id,device_id,local_date,reward_expires_at,revision,
                                             world_revision,stars,completed_count,started_at)
                values (?,?,'synthetic',current_date,now()+interval '1 day',2,1,1,1,now())
                """, receipt.sessionId(), receipt.planId());
        db.update("""
                insert into learning.receipt(id,session_id,plan_id,slot,client_commit_id,request_digest,response,completed_at)
                values(?,?,?,1,'synthetic',?,?::jsonb,now())
                """, receipt.receiptId(), receipt.sessionId(), receipt.planId(), "b".repeat(64), json.writeValueAsString(receipt));
        db.update("insert into learning.star_award(receipt_id,session_id,slot,amount) values(?,?,1,1)",
                receipt.receiptId(), receipt.sessionId());
    }

    private Map<String, Object> completionSnapshot(Receipt receipt) {
        return db.queryForMap("""
                select r.response::text, a.amount, s.stars from learning.receipt r
                join learning.star_award a on a.receipt_id=r.id join learning.session s on s.id=r.session_id
                where r.id=?
                """, receipt.receiptId());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Synthetic worker wait expired");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class TestClock extends Clock {
        volatile Instant now = Instant.parse("2099-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}

