package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.GenerationJobs.*;

import com.planb.emotionfriend.api.learning.LearningContracts.Receipt;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reserves voice work from the committed ledger receipt and its server-owned reaction binding. */
public final class ReactionJobs {
    public record ReviewedReaction(UUID ref, String text, String textHash, String speakerId, UUID settingsRef) {
        public ReviewedReaction {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(settingsRef);
            if (speakerId == null || speakerId.isBlank() || text == null || PreviewBinding.blank(text)
                    || !ProductHashes.textHash(text).equals(textHash)) {
                throw new IllegalArgumentException("Invalid reviewed reaction binding");
            }
        }
        @Override public String toString() { return "ReviewedReaction[redacted]"; }
    }

    public record Accepted(Receipt receipt, long bindingRevision, long authorityRevision, ReviewedReaction reaction) {
        public Accepted {
            Objects.requireNonNull(receipt);
            Objects.requireNonNull(receipt.receiptId());
            Objects.requireNonNull(receipt.completedAt());
        }
    }

    @FunctionalInterface
    public interface CommittedReceipts {
        /**
         * Resolves a committed receipt under current child/scope access. In a job transaction the
         * implementation must also lock the current authority/binding until commit.
         */
        Optional<Accepted> find(Scope scope, UUID receiptId);
    }

    @FunctionalInterface
    public interface VoiceWorker {
        Result execute(Job claim, ReviewedReaction reaction);
    }

    public enum Availability { RESERVED, NOT_AVAILABLE }
    public record Reservation(Availability availability, Job job) {}

    private final CommittedReceipts receipts;
    private final GenerationJobs jobs;

    public ReactionJobs(Repository repository, CommittedReceipts receipts, VoiceWorker worker) {
        this.receipts = Objects.requireNonNull(receipts);
        Objects.requireNonNull(worker);
        this.jobs = new GenerationJobs(Kind.REACTION_MEDIA, repository,
                (request, now) -> new Fingerprint(ProductHashes.textHash(request.input().sourceRef().toString()), null),
                request -> current(request).isPresent() ? Access.CURRENT : Access.REVOKED, claim ->
                current(claim.request()).map(accepted -> worker.execute(claim, accepted.reaction()))
                        .orElseGet(() -> new Result(State.FAILED, null)));
    }

    public Reservation reserve(Scope scope, UUID receiptId, Instant deadline) {
        requireReactionScope(scope);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reaction reservation requires a committed receipt");
        }
        Accepted accepted = lookup(scope, receiptId).orElseThrow(() -> new IllegalArgumentException("Receipt unavailable"));
        if (accepted.reaction() == null) return new Reservation(Availability.NOT_AVAILABLE, null);
        return new Reservation(Availability.RESERVED, jobs.reserve(new Request(scope, input(accepted)), deadline));
    }

    public Optional<Job> status(Scope scope, UUID jobId) {
        return jobs.status(jobId, scope);
    }

    public boolean dispatch(UUID jobId) {
        return jobs.dispatch(jobId);
    }

    private Optional<Accepted> current(Request request) {
        return lookup(request.scope(), request.input().sourceRef())
                .filter(accepted -> accepted.reaction() != null && input(accepted).equals(request.input()));
    }

    private Optional<Accepted> lookup(Scope scope, UUID receiptId) {
        return receipts.find(scope, receiptId).filter(accepted -> receiptId.equals(accepted.receipt().receiptId()));
    }

    private static Input input(Accepted accepted) {
        ReviewedReaction reaction = accepted.reaction();
        return new Input(accepted.receipt().receiptId(), accepted.bindingRevision(), accepted.authorityRevision(),
                reaction.settingsRef(), new VoiceBinding(reaction.ref(), reaction.textHash(), reaction.speakerId()));
    }

    private static void requireReactionScope(Scope scope) {
        if (scope.kind() != Kind.REACTION_MEDIA) throw new IllegalArgumentException("Reaction scope required");
    }
}

