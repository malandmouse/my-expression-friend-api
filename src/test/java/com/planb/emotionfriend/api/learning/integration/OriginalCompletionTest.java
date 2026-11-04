package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.OriginalCompletion.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.learning.ConfirmedExpressions;
import com.planb.emotionfriend.api.learning.LearningContracts.FinalizeRequest;
import com.planb.emotionfriend.api.learning.LearningContracts.Receipt;
import com.planb.emotionfriend.api.learning.LearningCurrent;
import com.planb.emotionfriend.api.learning.RuntimeContracts.Commit;
import com.planb.emotionfriend.api.learning.integration.CompletionBoundary.Command;
import com.planb.emotionfriend.api.learning.integration.CurrentJobGuard.Settings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OriginalCompletionTest {
    private static final Instant NOW = Instant.parse("2099-01-01T00:00:00Z");
    private static final String ORIGINAL = "  원문 그대로.\n";
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus tx = new SimpleTransactionStatus();
    private final LearningCurrent current = mock(LearningCurrent.class);
    private final Ledger ledger = mock(Ledger.class);
    private final Evidence evidence = mock(Evidence.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ConfirmedExpressions proofs = new ConfirmedExpressions(clock);
    private final OriginalCompletion boundary = new OriginalCompletion(manager, current, proofs, ledger, evidence, clock);
    private final Settings settings = new Settings(UUID.randomUUID(), 8);
    private final UUID child = UUID.randomUUID(), session = UUID.randomUUID(), attempt = UUID.randomUUID();
    private final String device = UUID.randomUUID().toString();
    private final ExpressionCurrent.Slot slot = new ExpressionCurrent.Slot(child, session, 1, attempt);
    private Command noCards;
    private Command afterCards;
    private LearningCurrent.Snapshot snapshot;
    private ReactionRoute route;
    private CardEvidence card;
    private Review review;
    private Receipt receipt;

    @BeforeEach void setup() throws Exception {
        var wire = ((ObjectNode) ProductContractTest.fixture("examples.json").get("catalog")).deepCopy();
        var contextWire = (ObjectNode) wire.get("context");
        contextWire.put("sourceText", ORIGINAL).put("sourceHash", ProductHashes.textHash(ORIGINAL));
        ((ObjectNode) wire.path("candidates").get(0)).put("answerText", ORIGINAL)
                .put("answerHash", ProductHashes.textHash(ORIGINAL));
        var catalog = PreviewBinding.validateCatalog(ProductContractTest.seal(wire));
        var context = catalog.context();
        var original = PreviewBinding.bind(catalog, ProductContractTest.request(wire, 0), catalog.cursor(), NOW);
        var proof = proofs.confirm(session, 1, device, 4);
        var actor = new ActorPrincipal(device, ActorPrincipal.Kind.CHILD, child.toString(), "synthetic-device",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), false);
        var query = new LearningCurrent.Query(actor, child, null, new LearningCurrent.SessionSlot(session, 1), Set.of());
        var progress = new LearningCurrent.Progress(UUID.fromString(actor.planId()), session, 1, attempt, 2, 0, 12,
                4, proof.confirmedExpressionRef(), List.of("choice"), "terminal", context.recognitionEmotionId(),
                context.recognitionTargetNpcId(), UUID.randomUUID(), UUID.randomUUID(), context.storyHash(), "a".repeat(64));
        snapshot = new LearningCurrent.Snapshot(child, UUID.randomUUID(), null, 1L, 6, Map.of(), progress);
        route = new ReactionRoute(progress.publicationId(), progress.scenarioVersionId(), progress.terminalSceneId(),
                "synthetic-ending", context.reactionNpcId());
        var reaction = new ReactionJobs.ReviewedReaction(UUID.randomUUID(), original.candidate().reactionText(),
                original.candidate().reactionHash(), context.reactionNpcId(), settings.ref());
        review = new Review(route, settings, Verdict.PASS, NOW.plusSeconds(60), reaction);
        var revision = new ExpressionCurrent.Revision(9, 12, 3, context.sourceVersion(), 5);
        var expressionProgress = new ExpressionCurrent.Progress(12, 3, true, context.clientSession(),
                context.storyHash(), context.inputHash(), context.path(), context.playedSpeakers(),
                new ExpressionCurrent.Recognition(progress.targetNpcId(), progress.emotionId(), route.reactionNpcId()),
                settings.ref());
        var source = new ExpressionCurrent.Source(context.sourceVersion(), ORIGINAL, context.sourceHash(), NOW.plusSeconds(60));
        var expression = new ExpressionCurrent.Current(revision, expressionProgress, source, catalog, List.of(), false);
        card = new CardEvidence(expression, snapshot, settings, original.bindingId(), original.reviewTargetHash(), review);
        var completion = new FinalizeRequest(proof.confirmedExpressionRef(), 4L, progress.choiceIds(),
                progress.terminalSceneId(), progress.emotionId(), "ORIGINAL", 2L, 0L, "synthetic-commit");
        var commit = new Commit(attempt, progress.manifestHash(), progress.scenarioVersionId(), 12L, completion);
        noCards = new Command(query, commit, null);
        afterCards = new Command(query, commit,
                new ExpressionCurrent.OriginalSelection(revision, original.candidate().id(), original.bindingId()));
        receipt = new Receipt(UUID.randomUUID(), session, progress.planId(), 1, NOW, 1, 1, 1, 1,
                proof.confirmedExpressionRef(), 4, "ORIGINAL", progress.targetNpcId(), "CHARACTER_REPLY");
        when(manager.getTransaction(any())).thenReturn(tx);
        when(current.read(query)).thenReturn(snapshot);
        when(evidence.lockPath(slot)).thenReturn(ExpressionPath.CARDS_REQUESTED);
        when(evidence.lockRoute(progress)).thenReturn(Optional.of(route));
        when(evidence.lockSettings(snapshot)).thenReturn(Optional.of(settings));
        when(evidence.lockCard(slot)).thenReturn(Optional.of(card));
        when(ledger.completeFirst(any(), any())).thenReturn(receipt);
    }

    @Test void reusesExactOriginalAndBindsTheReviewedRefBeforeCommit() {
        assertThat(ExpressionCurrent.requireOriginal(card.current(), afterCards.selection(), NOW).answerText())
                .isEqualTo(ORIGINAL);
        assertThat(boundary.complete(afterCards)).isEqualTo(receipt);
        var order = inOrder(manager, current, ledger, evidence);
        order.verify(manager).getTransaction(any());
        order.verify(current).lockDomain();
        order.verify(ledger).lockAndReplay(afterCards);
        order.verify(current).read(afterCards.query());
        order.verify(evidence).lockCard(slot);
        order.verify(ledger).completeFirst(afterCards, Optional.of(new Binding(review.reaction().ref(), card.bindingId())));
        order.verify(manager).commit(tx);
        verify(evidence, never()).lockPublishedReview(any());
    }

    @Test void cardlessOriginalUsesOnlyPublishedReviewOrCompletesWithoutReaction() {
        when(evidence.lockPath(slot)).thenReturn(ExpressionPath.CARDLESS);
        assertThat(boundary.complete(noCards)).isEqualTo(receipt);
        verify(ledger).completeFirst(noCards, Optional.empty());
        when(evidence.lockPublishedReview(route)).thenReturn(Optional.of(review));
        assertThat(boundary.complete(noCards)).isEqualTo(receipt);
        verify(ledger).completeFirst(noCards, Optional.of(new Binding(review.reaction().ref(), null)));
        verify(evidence, never()).lockCard(any());
    }

    @Test void omittingSelectionDoesNotBypassAnExistingCardRequest() {
        assertThatThrownBy(() -> boundary.complete(noCards)).isInstanceOf(ExpressionCurrent.Conflict.class);
        verify(ledger, never()).completeFirst(any(), any());
        verify(evidence, never()).lockPublishedReview(any());
    }

    @Test void aPublishedReviewForADifferentEndingIsUnavailable() {
        when(evidence.lockPath(slot)).thenReturn(ExpressionPath.CARDLESS);
        var otherEnding = new ReactionRoute(route.publicationId(), route.scenarioVersionId(),
                route.terminalSceneId(), "other-ending", route.reactionNpcId());
        when(evidence.lockPublishedReview(route)).thenReturn(Optional.of(
                new Review(otherEnding, settings, Verdict.PASS, review.expiresAt(), review.reaction())));
        assertThat(boundary.complete(noCards)).isEqualTo(receipt);
        verify(ledger).completeFirst(noCards, Optional.empty());
    }

    @Test void rejectsNonPassExpiredAndWrongNpcCardEvidence() {
        var otherNpc = new ReactionJobs.ReviewedReaction(review.reaction().ref(), review.reaction().text(),
                review.reaction().textHash(), "other-npc", settings.ref());
        for (Review invalid : List.of(
                new Review(route, settings, Verdict.REJECT, review.expiresAt(), review.reaction()),
                new Review(route, settings, Verdict.UNCERTAIN, review.expiresAt(), review.reaction()),
                new Review(route, settings, Verdict.PASS, NOW, review.reaction()),
                new Review(route, settings, Verdict.PASS, review.expiresAt(), otherNpc))) {
            when(evidence.lockCard(slot)).thenReturn(Optional.of(new CardEvidence(
                    card.current(), snapshot, settings, card.bindingId(), card.reviewTargetHash(), invalid)));
            assertThatThrownBy(() -> boundary.complete(afterCards)).isInstanceOf(ExpressionCurrent.Conflict.class);
        }
        verify(ledger, never()).completeFirst(any(), any());
        verify(manager, times(4)).rollback(tx);
    }

    @Test void rejectsStaleProfileSettingsSelectionAndBinding() {
        when(evidence.lockSettings(snapshot)).thenReturn(Optional.of(new Settings(settings.ref(), 9)));
        assertThatThrownBy(() -> boundary.complete(afterCards)).isInstanceOf(ExpressionCurrent.Conflict.class);
        when(evidence.lockSettings(snapshot)).thenReturn(Optional.of(settings));
        var expression = card.current();
        for (List<String> order : List.of(List.of("a", "b"), List.of("b", "a"))) {
            var changed = new ExpressionCurrent.Current(expression.revision(), expression.progress(),
                    expression.source(), expression.catalog(), order, false);
            when(evidence.lockCard(slot)).thenReturn(Optional.of(new CardEvidence(
                    changed, snapshot, settings, card.bindingId(), card.reviewTargetHash(), review)));
            assertThatThrownBy(() -> boundary.complete(afterCards)).isInstanceOf(ExpressionCurrent.Conflict.class);
        }
        var oldProfile = new LearningCurrent.Snapshot(child, snapshot.guardianId(), null, 1L, 5, Map.of(), snapshot.progress());
        when(evidence.lockCard(slot)).thenReturn(Optional.of(new CardEvidence(
                expression, oldProfile, settings, card.bindingId(), card.reviewTargetHash(), review)));
        assertThatThrownBy(() -> boundary.complete(afterCards)).isInstanceOf(ExpressionCurrent.Conflict.class);
        when(evidence.lockCard(slot)).thenReturn(Optional.of(card));
        var changedBinding = new ExpressionCurrent.OriginalSelection(expression.revision(),
                afterCards.selection().candidateId(), "f".repeat(64));
        assertThatThrownBy(() -> boundary.complete(new Command(afterCards.query(), afterCards.commit(), changedBinding)))
                .isInstanceOf(ExpressionCurrent.Conflict.class);
        verify(ledger, never()).completeFirst(any(), any());
    }

    @Test void authorizedReplaySkipsCandidateAndProgressAndDenialStillWins() {
        when(ledger.lockAndReplay(afterCards)).thenReturn(Optional.of(receipt))
                .thenThrow(new ApiException(ErrorCode.CONSENT_REQUIRED));
        assertThat(boundary.complete(afterCards)).isSameAs(receipt);
        assertThatThrownBy(() -> boundary.complete(afterCards)).isInstanceOf(ApiException.class);
        verify(current, never()).read(any());
        verifyNoInteractions(evidence);
        verify(ledger, never()).completeFirst(any(), any());
    }

    @Test void rejectsAnExpiredSourceOrASelectionForAnEarlierCatalogRevision() {
        var expression = card.current();
        var old = expression.source();
        var expired = new ExpressionCurrent.Source(old.version(), old.text(), old.hash(), NOW);
        var changed = new ExpressionCurrent.Current(expression.revision(), expression.progress(),
                expired, expression.catalog(), List.of(), false);
        when(evidence.lockCard(slot)).thenReturn(Optional.of(new CardEvidence(
                changed, snapshot, settings, card.bindingId(), card.reviewTargetHash(), review)));
        assertThatThrownBy(() -> boundary.complete(afterCards)).isInstanceOf(ExpressionCurrent.Conflict.class);

        var revision = expression.revision();
        var newer = new ExpressionCurrent.Revision(revision.current() + 1, revision.progress(),
                revision.authority(), revision.source(), revision.catalog() + 1);
        changed = new ExpressionCurrent.Current(newer, expression.progress(),
                expression.source(), expression.catalog(), List.of(), false);
        when(evidence.lockCard(slot)).thenReturn(Optional.of(new CardEvidence(
                changed, snapshot, settings, card.bindingId(), card.reviewTargetHash(), review)));
        assertThatThrownBy(() -> boundary.complete(afterCards)).isInstanceOf(ExpressionCurrent.Conflict.class);
        verify(ledger, never()).completeFirst(any(), any());
    }

    @Test void aBindingWriteFailureRequestsRollback() {
        when(ledger.completeFirst(any(), any())).thenThrow(new IllegalStateException("Synthetic binding failure"));
        assertThatThrownBy(() -> boundary.complete(afterCards)).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(tx);
        verify(manager, never()).commit(any());
    }
}
