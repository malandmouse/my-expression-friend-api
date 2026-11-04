package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.PreviewBinding.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Internal expression rules over a server-owned current aggregate. The store owns atomic replacement. */
public final class ExpressionCurrent {
    public static final int DEFAULT_CARD_COUNT = 6;

    public enum Operation { PREPARE, RECOMPOSE, REFRESH, REUSE_ORIGINAL }

    public record Slot(UUID childId, UUID sessionId, int slot, UUID attemptId) {
        public Slot {
            Objects.requireNonNull(childId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(attemptId);
            if (slot < 1 || slot > 5) throw new IllegalArgumentException("Invalid slot");
        }
    }

    public record Recognition(String targetNpcId, String emotionId, String reactionNpcId) {
        public Recognition {
            Objects.requireNonNull(targetNpcId);
            Objects.requireNonNull(emotionId);
            Objects.requireNonNull(reactionNpcId);
        }
    }

    /** Supplied by server progress/authority, with a monotone revision for each input domain. */
    public record Progress(long revision, long authorityRevision, boolean allowed, String clientSession,
                           String storyHash, String inputHash, List<String> path, List<String> speakers,
                           Recognition recognition, UUID settingsRef) {
        public Progress {
            path = List.copyOf(path);
            speakers = List.copyOf(speakers);
            Objects.requireNonNull(clientSession);
            Objects.requireNonNull(settingsRef);
            if (revision < 1 || authorityRevision < 1 || !ProductHashes.isHash(storyHash)
                    || (inputHash != null && !ProductHashes.isHash(inputHash))
                    || path.size() != speakers.size() || (recognition != null && path.isEmpty())) {
                throw new IllegalArgumentException("Invalid progress evidence");
            }
        }
    }

    public record Source(long version, String text, String hash, Instant expiresAt) {
        @Override public String toString() { return "Source[redacted]"; }
    }

    public record Revision(long current, long progress, long authority, long source, long catalog) {}

    public record Current(Revision revision, Progress progress, Source source, Catalog catalog, List<String> orderedCardIds,
                          boolean composedCurrent) {
        public Current { orderedCardIds = List.copyOf(orderedCardIds); }

        private Current change(Progress next, Source source, long sourceVersion, Catalog catalog,
                               long catalogRevision, List<String> orderedCardIds, boolean composedCurrent) {
            var revision = new Revision(Math.incrementExact(this.revision.current()), next.revision(),
                    next.authorityRevision(), sourceVersion, catalogRevision);
            return new Current(revision, next, source, catalog, orderedCardIds, composedCurrent);
        }
    }

    /** The source, progress, catalog and candidate view have a single atomic current revision. */
    public interface Store {
        Optional<Current> read(Slot slot);
        Current initialize(Slot slot, Current current);
        Current compareAndUpdate(Slot slot, Revision expected, UnaryOperator<Current> change);
    }

    /** An internal request produced from current state. No request body can supply the worker ticket. */
    public static final class Plan {
        private final Slot slot;
        private final Operation operation;
        private final Current current;
        private final int cardCount;

        private Plan(Slot slot, Operation operation, Current current, int cardCount) {
            this.slot = slot;
            this.operation = operation;
            this.current = current;
            this.cardCount = cardCount;
        }

        public Slot slot() { return slot; }
        public Operation operation() { return operation; }
        public Current current() { return current; }
        public int cardCount() { return cardCount; }
        public boolean needsWorker() { return operation != Operation.REUSE_ORIGINAL; }
    }

    /** A completed operation can carry a non-selectable catalog. */
    public record Completed(Quality quality, Revision revision, Candidate reusableOriginal) {}

    public record CandidateProof(Slot slot, Revision revision, Bound bound) {}

    public record OriginalSelection(Revision revision, String candidateId, String bindingId) {
        public OriginalSelection {
            Objects.requireNonNull(revision);
            if (candidateId == null || candidateId.isBlank() || !ProductHashes.isHash(bindingId)) {
                throw new IllegalArgumentException("Invalid original selection");
            }
        }
    }

    private final Store store;
    private final Clock clock;

    public ExpressionCurrent(Store store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public Current initialize(Slot slot, Progress progress) {
        return store.initialize(slot, new Current(new Revision(1, progress.revision(), progress.authorityRevision(),
                0, 0), progress, null, null, List.of(), false));
    }

    /** Trusted progress update. Revocation and other input changes invalidate outstanding plans. */
    public Current recordProgress(Slot slot, Revision expected, Progress progress) {
        return store.compareAndUpdate(slot, expected, current -> {
            Progress previous = current.progress();
            boolean sameInput = progress.revision() == previous.revision()
                    && previous.clientSession().equals(progress.clientSession())
                    && previous.storyHash().equals(progress.storyHash())
                    && Objects.equals(previous.inputHash(), progress.inputHash())
                    && previous.path().equals(progress.path()) && previous.speakers().equals(progress.speakers())
                    && Objects.equals(previous.recognition(), progress.recognition())
                    && previous.settingsRef().equals(progress.settingsRef());
            require(progress.revision() >= previous.revision()
                    && progress.authorityRevision() >= previous.authorityRevision()
                    && (sameInput || progress.revision() > previous.revision())
                    && (progress.allowed() == previous.allowed()
                        || progress.authorityRevision() > previous.authorityRevision()), "Non-monotone progress");
            if (progress.equals(previous)) return current;
            return current.change(progress, null, current.revision().source(), null,
                    Math.incrementExact(current.revision().catalog()), List.of(), false);
        });
    }

    /** The expiry comes from an explicit caller policy. The source version is issued here. */
    public Current saveSource(Slot slot, Revision expected, String text, Instant expiresAt) {
        require(text != null && !blank(text) && text.codePointCount(0, text.length()) <= 400, "Invalid source");
        String hash = ProductHashes.textHash(text);
        return store.compareAndUpdate(slot, expected, current -> {
            require(current.progress().allowed() && current.progress().recognition() != null, "Source unavailable");
            require(clock.instant().isBefore(expiresAt), "Source expired");
            long version = Math.incrementExact(current.revision().source());
            return current.change(current.progress(), new Source(version, text, hash, expiresAt), version, null,
                    Math.incrementExact(current.revision().catalog()), List.of(), false);
        });
    }

    public Current select(Slot slot, Revision expected, List<String> orderedIds) {
        var selected = List.copyOf(orderedIds);
        return store.compareAndUpdate(slot, expected, current -> {
            requireLiveCatalog(current, clock.instant());
            require(current.catalog().quality() == Quality.SELECTABLE && selected.size() <= 4
                    && new HashSet<>(selected).size() == selected.size()
                    && selected.stream().allMatch(id -> current.catalog().cards().stream().anyMatch(c -> c.id().equals(id))),
                    "Invalid selection");
            if (selected.equals(current.orderedCardIds())) return current;
            // Keep the deck, but require a new worker result before a composed candidate can bind.
            return current.change(current.progress(), current.source(), current.revision().source(), current.catalog(),
                    current.revision().catalog(), selected, false);
        });
    }

    public Plan plan(Slot slot, Revision expected, Operation operation, Integer requestedCount) {
        Current current = load(slot, expected);
        Instant now = clock.instant();
        requireLiveSource(current, now);
        int count = 0;
        switch (operation) {
            case PREPARE -> {
                require(current.catalog() == null && current.orderedCardIds().isEmpty(), "Catalog already exists");
                count = cardCount(requestedCount);
            }
            case REFRESH -> {
                requireLiveCatalog(current, now);
                count = cardCount(requestedCount);
            }
            case RECOMPOSE -> {
                requireLiveCatalog(current, now);
                require(requestedCount == null && current.catalog().quality() == Quality.SELECTABLE
                        && !current.orderedCardIds().isEmpty(), "Recomposition requires a selectable deck and selection");
            }
            case REUSE_ORIGINAL -> {
                requireLiveCatalog(current, now);
                require(requestedCount == null && current.catalog().quality() == Quality.SELECTABLE
                        && current.orderedCardIds().isEmpty(), "Original candidate unavailable");
            }
        }
        return new Plan(slot, operation, current, count);
    }

    public Completed reuseOriginal(Plan plan) {
        require(!plan.needsWorker(), "Worker operation");
        Current current = load(plan.slot(), plan.current().revision());
        requireLiveCatalog(current, clock.instant());
        return new Completed(Quality.SELECTABLE, current.revision(), current.catalog().original());
    }

    /** Catalog validation precedes admission. The ticket is captured before invoking a trusted adapter. */
    public Completed accept(Plan plan, Catalog result) {
        require(plan.needsWorker(), "Original reuse requires no result");
        Current saved = store.compareAndUpdate(plan.slot(), plan.current().revision(), current -> {
            Instant now = clock.instant();
            requireLiveSource(current, now);
            if (plan.operation() != Operation.PREPARE) requireLiveCatalog(current, now);
            requireContext(current, result.context(), now);
            requireDeck(plan, current, result);
            return current.change(current.progress(), current.source(), current.revision().source(), result,
                    Math.incrementExact(current.revision().catalog()), current.orderedCardIds(),
                    plan.operation() == Operation.RECOMPOSE && result.quality() == Quality.SELECTABLE);
        });
        return new Completed(result.quality(), saved.revision(), null);
    }

    /** Proof for a future ledger adapter. It must be rechecked at the atomic completion write. */
    public CandidateProof candidate(Slot slot, Revision expected, JsonNode selection) {
        Current current = load(slot, expected);
        Instant now = clock.instant();
        requireLiveCatalog(current, now);
        var bound = PreviewBinding.bind(current.catalog(), selection, current.catalog().cursor(), now);
        require(current.orderedCardIds().equals(bound.candidate().orderedCardIds())
                && (bound.candidate().mode() == Mode.ORIGINAL
                    || current.composedCurrent()),
                "Selection requires a current reviewed candidate");
        return new CandidateProof(slot, current.revision(), bound);
    }

    /** Caller must hold the store's current-state lock through the completion write. */
    static Candidate requireOriginal(Current current, OriginalSelection selection, Instant now) {
        require(current.revision().equals(selection.revision()), "Stale current revision");
        requireLiveCatalog(current, now);
        requireContext(current, current.catalog().context(), now);
        require(current.catalog().quality() == Quality.SELECTABLE && current.orderedCardIds().isEmpty(),
                "Original candidate unavailable");
        Candidate candidate = current.catalog().original();
        require(candidate.id().equals(selection.candidateId())
                && bindingId(current.catalog(), candidate).equals(selection.bindingId()), "Stale candidate binding");
        return candidate;
    }

    private Current load(Slot slot, Revision expected) {
        Current current = store.read(slot).orElseThrow(() -> new Conflict("Current expression unavailable"));
        require(current.revision().equals(expected), "Stale current revision");
        return current;
    }

    private static void requireDeck(Plan plan, Current current, Catalog result) {
        Catalog prior = current.catalog();
        var composed = result.candidates().stream().filter(candidate -> candidate.mode() == Mode.AI_COMPOSED).toList();
        switch (plan.operation()) {
            case PREPARE -> require(composed.isEmpty() && result.cards().size() == plan.cardCount(), "Invalid initial deck");
            case REFRESH -> {
                require(composed.isEmpty() && result.cards().size() == plan.cardCount()
                        && !result.context().catalogId().equals(prior.context().catalogId()), "Invalid refreshed deck");
                for (String id : current.orderedCardIds()) {
                    require(result.cards().contains(prior.cards().stream().filter(card -> card.id().equals(id))
                            .findFirst().orElseThrow()), "Refresh changed a selected card");
                }
            }
            case RECOMPOSE -> require(prior.context().catalogId().equals(result.context().catalogId())
                    && prior.cards().equals(result.cards()) && composed.size() == 1
                    && composed.getFirst().orderedCardIds().equals(current.orderedCardIds()), "Invalid recomposition");
            default -> throw new IllegalStateException("Operation does not accept a worker result");
        }
    }

    private static void requireContext(Current current, Context context, Instant now) {
        var p = current.progress();
        var s = current.source();
        var recognition = p.recognition();
        require(context.clientSession().equals(p.clientSession()) && context.storyHash().equals(p.storyHash())
                && Objects.equals(context.inputHash(), p.inputHash()) && context.path().equals(p.path())
                && context.playedSpeakers().equals(p.speakers())
                && context.recognitionTargetNpcId().equals(recognition.targetNpcId())
                && context.recognitionEmotionId().equals(recognition.emotionId())
                && context.reactionNpcId().equals(recognition.reactionNpcId())
                && context.sourceVersion() == s.version() && context.sourceHash().equals(s.hash())
                && context.sourceText().equals(s.text()) && now.isBefore(context.expiresAt()), "Stale worker context");
    }

    private static void requireLiveSource(Current current, Instant now) {
        require(current.progress().allowed() && current.progress().recognition() != null && current.source() != null
                && now.isBefore(current.source().expiresAt()), "Current source unavailable");
    }

    private static void requireLiveCatalog(Current current, Instant now) {
        requireLiveSource(current, now);
        require(current.catalog() != null && now.isBefore(current.catalog().context().expiresAt()), "Current catalog unavailable");
    }

    private static int cardCount(Integer count) {
        int requested = count == null ? DEFAULT_CARD_COUNT : count;
        require(requested >= 4 && requested <= 8, "Invalid card count");
        return requested;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new Conflict(message);
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}

