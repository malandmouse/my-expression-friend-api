package com.planb.emotionfriend.api.learning.integration;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal job lifecycle. Construct with an explicit worker, fingerprint policy and currentness guard. */
public final class GenerationJobs {
    public enum Kind { AUTHORING, REACTION_MEDIA }
    public enum State {
        QUEUED, RUNNING, RESULT_READY, HELD, FAILED, CANCELLED, OUTCOME_UNKNOWN;

        boolean pending() { return this == QUEUED || this == RUNNING; }
    }
    public enum Access { CURRENT, STALE, REVOKED }

    public record Scope(UUID childId, UUID requesterId, Kind kind) {
        public Scope {
            Objects.requireNonNull(childId);
            Objects.requireNonNull(requesterId);
            Objects.requireNonNull(kind);
        }
    }

    public record VoiceBinding(UUID reactionRef, String textHash, String speakerId) {
        public VoiceBinding {
            Objects.requireNonNull(reactionRef);
            if (!ProductHashes.isHash(textHash) || speakerId == null || speakerId.isBlank()) {
                throw new IllegalArgumentException("Invalid voice binding");
            }
        }
    }

    /** Server-side references only. Their mapping and envelope are supplied by the adapter. */
    public record Input(UUID sourceRef, long inputRevision, long authorityRevision, UUID settingsRef,
                        VoiceBinding voiceBinding) {
        public Input {
            Objects.requireNonNull(sourceRef);
            Objects.requireNonNull(settingsRef);
            if (inputRevision < 1 || authorityRevision < 1) throw new IllegalArgumentException("Invalid job revision");
        }
    }

    public record Request(Scope scope, Input input) {
        public Request {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(input);
            if ((scope.kind() == Kind.REACTION_MEDIA) != (input.voiceBinding() != null)) {
                throw new IllegalArgumentException("Voice binding is required for reaction jobs");
            }
        }
    }

    /** A null retention deadline keeps the fingerprint for the lifetime of its job record. */
    public record Fingerprint(String key, Instant retainUntil) {
        public Fingerprint {
            if (!ProductHashes.isHash(key)) throw new IllegalArgumentException("Invalid business fingerprint");

        }
    }

    @FunctionalInterface
    public interface FingerprintPolicy {
        Fingerprint forRequest(Request request, Instant now);
    }

    @FunctionalInterface
    public interface Guard {
        /** Runs in the repository transaction. Adapters must lock the live authority and input until commit. */
        Access check(Request request);
    }

    public record Artifact(UUID id, String bytesHash) {
        public Artifact {
            Objects.requireNonNull(id);
            if (!ProductHashes.isHash(bytesHash)) throw new IllegalArgumentException("Invalid artifact hash");
        }
    }

    public record Result(State state, Artifact artifact) {
        public Result {
            Objects.requireNonNull(state);
            if ((state == State.RESULT_READY) != (artifact != null)
                    || state.pending() || state == State.CANCELLED) {
                throw new IllegalArgumentException("Invalid worker result");
            }
        }
    }

    public record Job(UUID id, Request request, State state, long version, Instant deadline, Artifact artifact) {}

    public interface Repository {
        Job reserve(Request request, Instant deadline, FingerprintPolicy fingerprints, Guard guard);
        Optional<Job> status(UUID id, Scope scope, Guard guard);
        Job cancel(UUID id, Scope scope, long version, Guard guard);
        Optional<Job> claim(UUID id, Kind kind, Guard guard);
        boolean settle(Job claim, Result result, Guard guard);
    }

    @FunctionalInterface
    public interface Worker {
        /** One invocation, with the persisted job ID and deadline. An ambiguous send returns OUTCOME_UNKNOWN. */
        Result execute(Job claim);
    }

    private final Kind kind;
    private final Repository jobs;
    private final FingerprintPolicy fingerprints;
    private final Guard guard;
    private final Worker worker;

    public GenerationJobs(Kind kind, Repository jobs, FingerprintPolicy fingerprints, Guard guard, Worker worker) {
        this.kind = Objects.requireNonNull(kind);
        this.jobs = Objects.requireNonNull(jobs);
        this.fingerprints = Objects.requireNonNull(fingerprints);
        this.guard = Objects.requireNonNull(guard);
        this.worker = Objects.requireNonNull(worker);
    }

    /** Joins an enclosing HTTP idempotency transaction when an adapter supplies one. */
    public Job reserve(Request request, Instant deadline) {
        requireKind(request.scope());
        return jobs.reserve(request, deadline, fingerprints, guard);
    }

    public Optional<Job> status(UUID id, Scope scope) {
        requireKind(scope);
        return jobs.status(id, scope, guard);
    }

    public Job cancel(UUID id, Scope scope, long expectedVersion) {
        requireKind(scope);
        return jobs.cancel(id, scope, expectedVersion, guard);
    }

    /** Called after reservation commit. Neither a DB transaction nor a lock spans worker execution. */
    public boolean dispatch(UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Dispatch requires a committed reservation");
        }
        var claim = jobs.claim(id, kind, guard);
        if (claim.isEmpty()) return false;
        Result result;
        try {
            result = Objects.requireNonNull(worker.execute(claim.get()));
        } catch (RuntimeException ambiguous) {
            result = new Result(State.OUTCOME_UNKNOWN, null);
        }
        return jobs.settle(claim.get(), result, guard);
    }

    private void requireKind(Scope scope) {
        if (scope.kind() != kind) throw new IllegalArgumentException("Wrong job scope");
    }
}
