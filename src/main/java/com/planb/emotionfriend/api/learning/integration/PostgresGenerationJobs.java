package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.GenerationJobs.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable metadata only. The caller supplies authority, fingerprint policy and the worker. */
public final class PostgresGenerationJobs implements Repository {
    private static final String SELECT = """
            select id, child_id, requester_id, kind, source_ref, input_revision, authority_revision,
                   settings_ref, reaction_ref, reaction_text_hash, reaction_speaker_id,
                   state, version, deadline, artifact_id, artifact_hash
            from learning.generation_job
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PostgresGenerationJobs(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    @Override
    public Job reserve(Request request, Instant deadline, FingerprintPolicy fingerprints, Guard guard) {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || (isolation != null && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED)) {
            throw new IllegalStateException("Reservation requires a writable READ_COMMITTED transaction");
        }
        return Objects.requireNonNull(transaction.execute(status -> {
            requireCurrent(request, guard);
            Scope scope = request.scope();
            Fingerprint fingerprint = fingerprints.forRequest(request, clock.instant());
            String lockKey = "learning.generation_job:" + scope.childId() + ":" + scope.kind() + ":" + fingerprint.key();
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, lockKey);
            Instant now = clock.instant();
            var prior = jdbc.query(SELECT + """
                    where child_id=? and kind=? and business_fingerprint=?
                      and (retain_until is null or state in ('QUEUED','RUNNING','OUTCOME_UNKNOWN') or retain_until>?)
                    order by created_at desc, id limit 1
                    """, PostgresGenerationJobs::read, scope.childId(), scope.kind().name(), fingerprint.key(),
                    Timestamp.from(now)).stream().findFirst();
            if (prior.isPresent()) {
                if (!prior.get().request().equals(request)) throw new Conflict("Business fingerprint already reserved");
                return prior.get();
            }
            if (!deadline.isAfter(now) || (fingerprint.retainUntil() != null && !fingerprint.retainUntil().isAfter(now))) {
                throw new IllegalArgumentException("Invalid reservation deadline or retention");
            }
            UUID id = UUID.randomUUID();
            Input input = request.input();
            VoiceBinding voice = input.voiceBinding();
            jdbc.update("""
                    insert into learning.generation_job
                      (id,child_id,requester_id,kind,source_ref,input_revision,authority_revision,settings_ref,
                       reaction_ref,reaction_text_hash,reaction_speaker_id,
                       business_fingerprint,retain_until,deadline,created_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, scope.childId(), scope.requesterId(), scope.kind().name(), input.sourceRef(),
                    input.inputRevision(), input.authorityRevision(), input.settingsRef(),
                    voice == null ? null : voice.reactionRef(), voice == null ? null : voice.textHash(),
                    voice == null ? null : voice.speakerId(), fingerprint.key(),
                    fingerprint.retainUntil() == null ? null : Timestamp.from(fingerprint.retainUntil()),
                    Timestamp.from(deadline), Timestamp.from(now));
            return find(id, false).orElseThrow();
        }));
    }

    @Override
    public Optional<Job> status(UUID id, Scope scope, Guard guard) {
        return Objects.requireNonNull(transaction.execute(status -> {
            var initial = find(id, false).filter(job -> job.request().scope().equals(scope));
            initial.ifPresent(job -> requireCurrent(job.request(), guard));
            return initial.map(job -> expire(find(id, true).orElseThrow()));
        }));
    }

    @Override
    public Job cancel(UUID id, Scope scope, long version, Guard guard) {
        return Objects.requireNonNull(transaction.execute(status -> {
            Job initial = find(id, false).filter(job -> job.request().scope().equals(scope))
                    .orElseThrow(() -> new Conflict("Job unavailable"));
            requireCurrent(initial.request(), guard);
            Job current = find(id, true).orElseThrow();
            if (current.state() == State.CANCELLED) return current;
            if (current.version() != version || !current.state().pending() || !clock.instant().isBefore(current.deadline())) {
                throw new Conflict("Job changed");
            }
            return transition(current, State.CANCELLED, null);
        }));
    }

    @Override
    public Optional<Job> claim(UUID id, Kind kind, Guard guard) {
        return Objects.requireNonNull(transaction.execute(status -> {
            var initial = find(id, false).filter(job -> job.request().scope().kind() == kind);
            if (initial.isEmpty() || !initial.get().state().pending()) return Optional.empty();
            Access access = Objects.requireNonNull(guard.check(initial.get().request()));
            Job job = expire(find(id, true).orElseThrow());
            if (job.state() != State.QUEUED) return Optional.empty();
            if (access != Access.CURRENT) {
                transition(job, State.CANCELLED, null);
                return Optional.empty();
            }
            return Optional.of(transition(job, State.RUNNING, null));
        }));
    }

    @Override
    public boolean settle(Job claim, Result result, Guard guard) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var initial = find(claim.id(), false);
            if (initial.isEmpty() || !matchesClaim(initial.get(), claim)) return false;
            Access access = Objects.requireNonNull(guard.check(initial.get().request()));
            Job job = expire(find(claim.id(), true).orElseThrow());
            if (!matchesClaim(job, claim)) return false;
            if (access != Access.CURRENT) {
                transition(job, State.CANCELLED, null);
                return false;
            }
            transition(job, result.state(), result.artifact());
            return true;
        }));
    }

    private boolean matchesClaim(Job job, Job claim) {
        return job.state() == State.RUNNING && job.equals(claim);
    }

    private Job expire(Job job) {
        if (!job.state().pending() || clock.instant().isBefore(job.deadline())) return job;
        return transition(job, job.state() == State.RUNNING ? State.OUTCOME_UNKNOWN : State.FAILED, null);
    }

    private Job transition(Job job, State state, Artifact artifact) {
        jdbc.update("""
                update learning.generation_job
                set state=?, version=version+1, artifact_id=?, artifact_hash=?,
                    retain_until=case when ? then null else retain_until end
                where id=?
                """, state.name(), artifact == null ? null : artifact.id(),
                artifact == null ? null : artifact.bytesHash(),
                job.state() == State.RUNNING && state == State.CANCELLED, job.id());
        return find(job.id(), false).orElseThrow();
    }

    private Optional<Job> find(UUID id, boolean lock) {
        return jdbc.query(SELECT + " where id=?" + (lock ? " for update" : ""), PostgresGenerationJobs::read,
                id).stream().findFirst();
    }

    private static Job read(ResultSet rs, int row) throws SQLException {
        var scope = new Scope(rs.getObject("child_id", UUID.class), rs.getObject("requester_id", UUID.class),
                Kind.valueOf(rs.getString("kind")));
        UUID reactionRef = rs.getObject("reaction_ref", UUID.class);
        VoiceBinding voice = reactionRef == null ? null : new VoiceBinding(reactionRef,
                rs.getString("reaction_text_hash"), rs.getString("reaction_speaker_id"));
        var input = new Input(rs.getObject("source_ref", UUID.class), rs.getLong("input_revision"),
                rs.getLong("authority_revision"), rs.getObject("settings_ref", UUID.class), voice);
        UUID artifactId = rs.getObject("artifact_id", UUID.class);
        return new Job(rs.getObject("id", UUID.class), new Request(scope, input), State.valueOf(rs.getString("state")),
                rs.getLong("version"), rs.getTimestamp("deadline").toInstant(),
                artifactId == null ? null : new Artifact(artifactId, rs.getString("artifact_hash")));
    }

    private static void requireCurrent(Request request, Guard guard) {
        if (guard.check(request) != Access.CURRENT) throw new Conflict("Job unavailable");
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}

