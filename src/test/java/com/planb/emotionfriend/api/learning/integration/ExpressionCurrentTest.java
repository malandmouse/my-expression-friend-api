package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.ExpressionCurrent.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpressionCurrentTest {
    private final Slot slot = new Slot(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    private final MemoryStore store = new MemoryStore();
    private final TestClock clock = new TestClock();
    private final ExpressionCurrent expressions = new ExpressionCurrent(store, clock);
    private ObjectNode fixture;
    private Progress progress;
    private Current source;

    @BeforeEach void setup() throws Exception {
        fixture = (ObjectNode) ProductContractTest.fixture("examples.json").get("catalog");
        var context = PreviewBinding.validateCatalog(fixture).context();
        progress = new Progress(9, 1, true, context.clientSession(), context.storyHash(), context.inputHash(),
                context.path(), context.playedSpeakers(),
                new Recognition(context.recognitionTargetNpcId(), context.recognitionEmotionId(), context.reactionNpcId()),
                UUID.randomUUID());
        Current initial = expressions.initialize(slot, progress);
        source = expressions.saveSource(slot, initial.revision(), context.sourceText(), clock.instant().plusSeconds(600));
    }

    @Test void prepareRecomposeRefreshAndRecomposeAgainPreserveSelectionAndRejectOldBindings() {
        var initial = originalOnly();
        var prepare = expressions.plan(slot, source.revision(), Operation.PREPARE, null);
        assertThat(prepare.cardCount()).isEqualTo(6);
        var ready = accept(prepare, initial);
        assertThatThrownBy(() -> expressions.select(slot, ready.revision(), List.of("a", "a")))
                .isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> expressions.select(slot, ready.revision(), List.of("a", "b", "c", "d", "e")))
                .isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> expressions.select(slot, ready.revision(), List.of("missing")))
                .isInstanceOf(Conflict.class);

        var selected = expressions.select(slot, ready.revision(), List.of("b", "a"));
        var compose = expressions.plan(slot, selected.revision(), Operation.RECOMPOSE, null);
        var composed = accept(compose, fixture);
        var oldRequest = ProductContractTest.request(fixture, 1);
        assertThat(expressions.candidate(slot, composed.revision(), oldRequest).bound().candidate().orderedCardIds())
                .containsExactly("b", "a");

        var refresh = expressions.plan(slot, composed.revision(), Operation.REFRESH, null);
        var replaced = originalOnly();
        ((ObjectNode) replaced.get("context")).put("catalogId", "synthetic-refreshed-catalog");
        ((ObjectNode) replaced.path("cards").get(2)).put("text", "잠시 멈춤");
        ProductContractTest.seal(replaced);
        var wrongRefresh = replaced.deepCopy();
        ((ObjectNode) wrongRefresh.path("cards").get(0)).put("text", "다른 말");
        assertThatThrownBy(() -> accept(refresh, ProductContractTest.seal(wrongRefresh))).isInstanceOf(Conflict.class);
        var refreshed = accept(refresh, replaced);
        var current = store.read(slot).orElseThrow();
        assertThat(current.source()).isEqualTo(source.source());
        assertThat(current.orderedCardIds()).containsExactly("b", "a");
        assertThat(current.catalog().cards().subList(0, 2)).isEqualTo(PreviewBinding.validateCatalog(fixture).cards().subList(0, 2));
        assertThat(current.composedCurrent()).isFalse();
        assertThatThrownBy(() -> expressions.candidate(slot, refreshed.revision(), oldRequest))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expressions.plan(slot, refreshed.revision(), Operation.REUSE_ORIGINAL, null))
                .isInstanceOf(Conflict.class);

        var updated = fixture.deepCopy();
        updated.set("context", replaced.get("context"));
        updated.set("cards", replaced.get("cards"));
        var composedAgain = accept(expressions.plan(slot, refreshed.revision(), Operation.RECOMPOSE, null),
                ProductContractTest.seal(updated));
        assertThat(expressions.candidate(slot, composedAgain.revision(), ProductContractTest.request(updated, 1))
                .bound().candidate().orderedCardIds()).containsExactly("b", "a");

        var reversed = expressions.select(slot, composedAgain.revision(), List.of("a", "b"));
        var restoredOrder = expressions.select(slot, reversed.revision(), List.of("b", "a"));
        assertThatThrownBy(() -> expressions.candidate(slot, restoredOrder.revision(), ProductContractTest.request(updated, 1)))
                .isInstanceOf(Conflict.class);
    }

    @Test void originalReuseUsesExactSourceAndHasNoWorkerResult() {
        var data = originalOnly();
        assertThatThrownBy(() -> expressions.plan(slot, source.revision(), Operation.PREPARE, 9))
                .isInstanceOf(Conflict.class);
        var ready = accept(expressions.plan(slot, source.revision(), Operation.PREPARE, 6), data);
        var reuse = expressions.plan(slot, ready.revision(), Operation.REUSE_ORIGINAL, null);
        assertThat(reuse.needsWorker()).isFalse();
        assertThat(expressions.reuseOriginal(reuse).reusableOriginal().answerText()).isEqualTo(source.source().text());
        assertThatThrownBy(() -> accept(reuse, data)).isInstanceOf(Conflict.class);
    }

    @Test void rejectedAndUncertainCanFinishButCannotBeSelectedOrBound() {
        for (String verdict : List.of("REJECT", "UNCERTAIN")) {
            var data = originalOnly();
            ((ObjectNode) data.get("review")).put("status", verdict);
            var ready = accept(expressions.plan(slot, source.revision(), Operation.PREPARE, null), ProductContractTest.seal(data));
            assertThat(ready.quality()).isNotEqualTo(PreviewBinding.Quality.SELECTABLE);
            assertThatThrownBy(() -> expressions.select(slot, ready.revision(), List.of("a")))
                    .isInstanceOf(Conflict.class);
            assertThatThrownBy(() -> expressions.candidate(slot, ready.revision(), ProductContractTest.request(data, 0)))
                    .isInstanceOf(IllegalArgumentException.class);
            source = expressions.saveSource(slot, ready.revision(), source.source().text(), clock.instant().plusSeconds(600));
            ((ObjectNode) fixture.get("context")).put("sourceVersion", source.source().version());
            ProductContractTest.seal(fixture);
        }
    }

    @Test void staleSourceProgressAuthorityAndExpiryRejectLateResults() {
        var plan = expressions.plan(slot, source.revision(), Operation.PREPARE, null);
        var changed = expressions.saveSource(slot, source.revision(), "changed", clock.instant().plusSeconds(600));
        assertThatThrownBy(() -> accept(plan, originalOnly())).isInstanceOf(Conflict.class);
        var next = new Progress(10, 1, true, progress.clientSession(), progress.storyHash(), progress.inputHash(),
                progress.path(), progress.speakers(), progress.recognition(), UUID.randomUUID());
        var afterProgress = expressions.recordProgress(slot, changed.revision(), next);
        assertThat(afterProgress.source()).isNull();

        var restored = expressions.saveSource(slot, afterProgress.revision(), source.source().text(), clock.instant().plusSeconds(2));
        var pending = expressions.plan(slot, restored.revision(), Operation.PREPARE, null);
        var updated = originalOnly();
        ((ObjectNode) updated.get("context")).put("sourceVersion", restored.source().version());
        ProductContractTest.seal(updated);
        clock.now = clock.instant().plusSeconds(2);
        assertThatThrownBy(() -> accept(pending, updated)).isInstanceOf(Conflict.class);

        var active = expressions.saveSource(slot, restored.revision(), restored.source().text(), clock.instant().plusSeconds(10));
        var revokedPlan = expressions.plan(slot, active.revision(), Operation.PREPARE, null);
        var revoked = new Progress(10, 2, false, next.clientSession(), next.storyHash(), next.inputHash(),
                next.path(), next.speakers(), next.recognition(), next.settingsRef());
        var blocked = expressions.recordProgress(slot, active.revision(), revoked);
        assertThatThrownBy(() -> accept(revokedPlan, updated)).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> expressions.saveSource(slot, blocked.revision(), "text", clock.instant().plusSeconds(60)))
                .isInstanceOf(Conflict.class);
    }

    @Test void sourceUsesCodePointsAndCatalogExpiryDoesNotExtendOnRead() {
        assertThatThrownBy(() -> expressions.saveSource(slot, source.revision(), "  ", clock.instant().plusSeconds(60)))
                .isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> expressions.saveSource(slot, source.revision(), "😀".repeat(401), clock.instant().plusSeconds(60)))
                .isInstanceOf(Conflict.class);
        var accepted = accept(expressions.plan(slot, source.revision(), Operation.PREPARE, null), originalOnly());
        var reuse = expressions.plan(slot, accepted.revision(), Operation.REUSE_ORIGINAL, null);
        clock.now = Instant.parse(fixture.path("context").path("expiresAt").asText());
        assertThatThrownBy(() -> expressions.reuseOriginal(reuse)).isInstanceOf(Conflict.class);
        var max = expressions.saveSource(slot, accepted.revision(), "😀".repeat(400), clock.instant().plusSeconds(60));
        assertThat(max.source().text().length()).isEqualTo(800);
    }

    @Test void twoResultsForTheSameRevisionHaveExactlyOneCasWinner() throws Exception {
        var plan = expressions.plan(slot, source.revision(), Operation.PREPARE, null);
        var result = PreviewBinding.validateCatalog(originalOnly());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> submit = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    expressions.accept(plan, result);
                    return true;
                } catch (Conflict expected) {
                    return false;
                }
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private Completed accept(Plan plan, ObjectNode catalog) {
        return expressions.accept(plan, PreviewBinding.validateCatalog(catalog));
    }

    private ObjectNode originalOnly() {
        var result = fixture.deepCopy();
        ((ArrayNode) result.path("candidates")).remove(1);
        ((ArrayNode) result.path("review").path("candidates")).remove(1);
        return ProductContractTest.seal(result);
    }

    private static final class MemoryStore implements Store {
        private final ConcurrentHashMap<Slot, Current> data = new ConcurrentHashMap<>();

        @Override public Optional<Current> read(Slot slot) { return Optional.ofNullable(data.get(slot)); }

        @Override public Current initialize(Slot slot, Current current) {
            if (data.putIfAbsent(slot, current) != null) throw new Conflict("Current already exists");
            return current;
        }

        @Override public Current compareAndUpdate(Slot slot, Revision expected, UnaryOperator<Current> change) {
            return data.compute(slot, (key, current) -> {
                if (current == null || !current.revision().equals(expected)) throw new Conflict("Stale current");
                return change.apply(current);
            });
        }
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2099-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}

