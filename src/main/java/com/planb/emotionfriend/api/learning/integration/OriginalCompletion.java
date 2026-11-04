package com.planb.emotionfriend.api.learning.integration;

import com.planb.emotionfriend.api.learning.ConfirmedExpressions;
import com.planb.emotionfriend.api.learning.LearningContracts.Receipt;
import com.planb.emotionfriend.api.learning.LearningCurrent;
import com.planb.emotionfriend.api.learning.integration.CurrentJobGuard.Settings;
import com.planb.emotionfriend.api.learning.integration.ReactionJobs.ReviewedReaction;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal orchestration. Requires a ledger adapter and authoritative evidence before registration. */
public final class OriginalCompletion implements CompletionBoundary {
    public record ReactionRoute(UUID publicationId, UUID scenarioVersionId, String terminalSceneId,
                                String endingType, String reactionNpcId) {
        public ReactionRoute {
            Objects.requireNonNull(publicationId);
            Objects.requireNonNull(scenarioVersionId);
            Objects.requireNonNull(terminalSceneId);
            if (endingType == null || endingType.isBlank() || reactionNpcId == null || reactionNpcId.isBlank()) {
                throw new IllegalArgumentException("Incomplete reaction route");
            }
        }
    }

    public enum Verdict { PASS, REJECT, UNCERTAIN }
    public enum ExpressionPath { CARDLESS, CARDS_REQUESTED }
    public record Review(ReactionRoute route, Settings settings, Verdict verdict, Instant expiresAt,
                         ReviewedReaction reaction) {}
    public record CardEvidence(ExpressionCurrent.Current current, LearningCurrent.Snapshot snapshot,
                               Settings settings, String bindingId, String reviewTargetHash, Review review) {}
    public record Binding(UUID reactionRef, String candidateBindingId) {}

    public interface Ledger {
        /**
         * Recheck current access and purpose consent, serialize first writes/replays, and return only
         * a matching committed receipt. A different command for a completed slot must conflict.
         * Replay ignores expired candidate/proof and advanced progress. Locks last through commit.
         */
        Optional<Receipt> lockAndReplay(Command command);

        /**
         * Delegate first completion to LearningLedger. If present, persist the binding in the same
         * transaction before returning. Any failure must roll back receipt, reward and binding.
         */
        Receipt completeFirst(Command command, Optional<Binding> binding);
    }

    /** Every read here must hold its mutable source locks through the enclosing ledger commit. */
    public interface Evidence {
        /** Read the server's path marker without loading a catalog. Serialize with card requests. */
        ExpressionPath lockPath(ExpressionCurrent.Slot slot);
        Optional<ReactionRoute> lockRoute(LearningCurrent.Progress progress);
        Optional<Settings> lockSettings(LearningCurrent.Snapshot snapshot);
        Optional<CardEvidence> lockCard(ExpressionCurrent.Slot slot);
        Optional<Review> lockPublishedReview(ReactionRoute route);
    }

    private final TransactionTemplate transaction;
    private final LearningCurrent current;
    private final ConfirmedExpressions proofs;
    private final Ledger ledger;
    private final Evidence evidence;
    private final Clock clock;

    public OriginalCompletion(PlatformTransactionManager manager, LearningCurrent current,
                              ConfirmedExpressions proofs, Ledger ledger, Evidence evidence, Clock clock) {
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.current = Objects.requireNonNull(current);
        this.proofs = Objects.requireNonNull(proofs);
        this.ledger = Objects.requireNonNull(ledger);
        this.evidence = Objects.requireNonNull(evidence);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public Receipt complete(Command command) {
        return Objects.requireNonNull(transaction.execute(status -> {
            current.lockDomain();
            return ledger.lockAndReplay(command).orElseGet(() -> completeFirst(command));
        }));
    }

    private Receipt completeFirst(Command command) {
        var live = current.read(command.query());
        var progress = live.progress();
        var commit = command.commit();
        var completion = commit.completion();
        require(progress != null && "ORIGINAL".equals(completion.expressionChoice())
                && progress.attemptId().equals(commit.attemptId())
                && progress.scenarioVersionId().equals(commit.scenarioVersionId())
                && progress.manifestHash().equals(commit.manifestHash())
                && Objects.equals(progress.checkpointRevision(), commit.expectedCheckpointRevision())
                && Objects.equals(progress.sessionRevision(), completion.expectedSessionRevision())
                && Objects.equals(progress.worldRevision(), completion.expectedWorldRevision())
                && Objects.equals(progress.draftVersion(), completion.draftVersion())
                && progress.choiceIds().equals(completion.selectedChoiceIds())
                && progress.terminalSceneId() != null && progress.terminalSceneId().equals(completion.terminalSceneId())
                && progress.emotionId() != null && progress.emotionId().equals(completion.selectedEmotionId())
                && progress.targetNpcId() != null && progress.confirmedExpressionRef() != null
                && progress.confirmedExpressionRef().equals(completion.confirmedExpressionRef()), "Stale completion");
        proofs.require(completion.confirmedExpressionRef(), progress.sessionId(), progress.slot(),
                command.query().actor().actorId(), progress.draftVersion());
        var slot = new ExpressionCurrent.Slot(live.childId(), progress.sessionId(), progress.slot(), progress.attemptId());
        var expectedPath = command.selection() == null ? ExpressionPath.CARDLESS : ExpressionPath.CARDS_REQUESTED;
        require(evidence.lockPath(slot) == expectedPath, "Completion path changed");
        var route = evidence.lockRoute(progress).filter(r -> r.publicationId().equals(progress.publicationId())
                && r.scenarioVersionId().equals(progress.scenarioVersionId())
                && r.terminalSceneId().equals(progress.terminalSceneId()));
        var binding = command.selection() == null ? published(live, route) : card(slot, command.selection(), live, route);
        return ledger.completeFirst(command, binding);
    }

    private Optional<Binding> published(LearningCurrent.Snapshot live, Optional<ReactionRoute> route) {
        return route.flatMap(r -> evidence.lockPublishedReview(r)
                .filter(review -> evidence.lockSettings(live)
                        .filter(settings -> validReview(review, r, settings, clock.instant())).isPresent())
                .map(review -> new Binding(review.reaction().ref(), null)));
    }

    private Optional<Binding> card(ExpressionCurrent.Slot slot, ExpressionCurrent.OriginalSelection selected,
                                   LearningCurrent.Snapshot live, Optional<ReactionRoute> route) {
        var progress = live.progress();
        var expectedRoute = route.orElseThrow(() -> new ExpressionCurrent.Conflict("Reaction route unavailable"));
        var settings = evidence.lockSettings(live)
                .orElseThrow(() -> new ExpressionCurrent.Conflict("Settings unavailable"));
        var card = evidence.lockCard(slot)
                .orElseThrow(() -> new ExpressionCurrent.Conflict("Reviewed candidate unavailable"));
        Instant now = clock.instant();
        var candidate = ExpressionCurrent.requireOriginal(card.current(), selected, now);
        var recognition = card.current().progress().recognition();
        var context = card.current().catalog().context();
        require(live.equals(card.snapshot()) && settings.equals(card.settings())
                && settings.ref().equals(card.current().progress().settingsRef())
                && progress.targetNpcId().equals(recognition.targetNpcId())
                && progress.emotionId().equals(recognition.emotionId())
                && expectedRoute.reactionNpcId().equals(recognition.reactionNpcId())
                && selected.bindingId().equals(card.bindingId())
                && card.current().catalog().reviewTargetHash().equals(card.reviewTargetHash())
                && validReview(card.review(), expectedRoute, settings, now)
                && candidate.answerText().equals(context.sourceText())
                && candidate.reactionText().equals(card.review().reaction().text())
                && candidate.reactionHash().equals(card.review().reaction().textHash())
                && candidate.speakerId().equals(card.review().reaction().speakerId()), "Stale reviewed candidate");
        return Optional.of(new Binding(card.review().reaction().ref(), selected.bindingId()));
    }

    private static boolean validReview(Review review, ReactionRoute route, Settings settings, Instant now) {
        return review != null && review.verdict() == Verdict.PASS && route.equals(review.route())
                && settings.equals(review.settings())
                && review.expiresAt() != null && now.isBefore(review.expiresAt())
                && review.reaction() != null && route.reactionNpcId().equals(review.reaction().speakerId())
                && settings.ref().equals(review.reaction().settingsRef());
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new ExpressionCurrent.Conflict(message);
    }
}
