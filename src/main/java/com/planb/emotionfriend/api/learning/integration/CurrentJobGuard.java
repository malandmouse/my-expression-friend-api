package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.GenerationJobs.*;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.learning.LearningCurrent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Compares the live version vector with a server-issued job binding inside repository transactions. */
public final class CurrentJobGuard implements Guard {
    public record Settings(UUID ref, long revision) {
        public Settings {
            Objects.requireNonNull(ref);
            if (revision < 1) throw new IllegalArgumentException("Invalid settings revision");
        }
    }

    public record SourceSettings(UUID sourceRef, long sourceRevision, Settings settings, VoiceBinding voiceBinding) {}

    public record Pin(Request request, LearningCurrent.Query query, LearningCurrent.Snapshot snapshot,
                      Settings settings) {
        public Pin {
            Objects.requireNonNull(request);
            Objects.requireNonNull(query);
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(settings);
            if (!query.childId().equals(request.scope().childId()) || !query.childId().equals(snapshot.childId())
                    || !query.actor().actorId().equals(request.scope().requesterId().toString())
                    || !settings.ref().equals(request.input().settingsRef())) {
                throw new IllegalArgumentException("Invalid job pin");
            }
        }
    }

    public interface Inputs {
        /** Resolve the original server pin under the domain lock and hold the input lock through commit. */
        Optional<Pin> lockPin(Request request);

        /** Resolve current source and settings under the same transaction and hold their locks through commit. */
        Optional<SourceSettings> lockCurrentInput(Pin pin);
    }

    private final LearningCurrent current;
    private final Inputs inputs;

    public CurrentJobGuard(LearningCurrent current, Inputs inputs) {
        this.current = Objects.requireNonNull(current);
        this.inputs = Objects.requireNonNull(inputs);
    }

    @Override public Access check(Request request) {
        current.lockDomain();
        var pin = inputs.lockPin(request).filter(p -> p.request().equals(request));
        if (pin.isEmpty()) return Access.STALE;
        LearningCurrent.Snapshot live;
        try {
            live = current.read(pin.get().query());
        } catch (ApiException unavailable) {
            return switch (unavailable.code()) {
                case AUTH_REQUIRED, TOKEN_EXPIRED, ACCOUNT_NOT_ACTIVE, ROLE_FORBIDDEN,
                     CHILD_ACCESS_DENIED, CONSENT_REQUIRED, RESOURCE_NOT_FOUND, RESOURCE_DELETED -> Access.REVOKED;
                case STALE_CHECKPOINT, STALE_WORLD_STATE, CHECKPOINT_EXPIRED, INVALID_BRANCH_PATH, CONTENT_REVOKED,
                     CONTENT_NOT_APPROVED, CONTENT_HASH_MISMATCH, POLICY_NOT_APPROVED,
                     POLICY_REVIEW_FORBIDDEN -> Access.STALE;
                default -> throw unavailable;
            };
        }
        var expected = new SourceSettings(request.input().sourceRef(), request.input().inputRevision(),
                pin.get().settings(), request.input().voiceBinding());
        return live.equals(pin.get().snapshot()) && inputs.lockCurrentInput(pin.get()).filter(expected::equals).isPresent()
                ? Access.CURRENT : Access.STALE;
    }
}
