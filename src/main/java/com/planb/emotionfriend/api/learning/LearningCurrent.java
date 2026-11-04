package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.children.ChildAccessPolicy;
import com.planb.emotionfriend.api.children.ChildContracts.ConsentScope;
import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.common.security.ChildAccessChecker.Capability;
import com.planb.emotionfriend.api.membership.MemberGuard;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Internal metadata from the live care and learning sources. Call with verified identity and server policy. */
@Component
public class LearningCurrent {
    public record SessionSlot(UUID sessionId, int slot) {
        public SessionSlot {
            Objects.requireNonNull(sessionId);
            if (slot < 1 || slot > 5) throw new IllegalArgumentException("Invalid slot");
        }
    }

    public record Query(ActorPrincipal actor, UUID childId, Capability adultCapability, SessionSlot slot,
                        Set<ConsentScope> purposes) {
        public Query {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(childId);
            purposes = Set.copyOf(purposes);
            if (actor.kind() == ActorPrincipal.Kind.CHILD) {
                Objects.requireNonNull(slot);
                if (adultCapability != null) throw new IllegalArgumentException("Unexpected adult capability");
            } else {
                Objects.requireNonNull(adultCapability);
            }
        }
        @Override public String toString() { return "LearningCurrent.Query[redacted]"; }
    }

    public record Progress(UUID planId, UUID sessionId, int slot, UUID attemptId,
                           long sessionRevision, long worldRevision, long checkpointRevision, long draftVersion,
                           UUID confirmedExpressionRef, List<String> choiceIds, String terminalSceneId,
                           String emotionId, String targetNpcId, UUID scenarioVersionId, UUID publicationId,
                           String manifestHash, String contentHash) {
        public Progress { choiceIds = List.copyOf(choiceIds); }
        @Override public String toString() { return "LearningCurrent.Progress[redacted]"; }
    }

    public record Snapshot(UUID childId, UUID guardianId, UUID therapistInvitationId, Long deviceGeneration,
                           long profileVersion, Map<ConsentScope, Long> consentRevisions, Progress progress) {
        public Snapshot { consentRevisions = Map.copyOf(consentRevisions); }
        @Override public String toString() { return "LearningCurrent.Snapshot[redacted]"; }
    }

    private final JdbcTemplate db;
    private final JsonMapper json;
    private final MemberGuard members;
    private final ChildAccessPolicy access;
    private final RuntimeLearningService runtime;
    private final LearningLedger ledger;
    private final CorePublicationService publications;

    public LearningCurrent(JdbcTemplate db, JsonMapper json, MemberGuard members, ChildAccessPolicy access,
                           RuntimeLearningService runtime, LearningLedger ledger, CorePublicationService publications) {
        this.db = db;
        this.json = json;
        this.members = members;
        this.access = access;
        this.runtime = runtime;
        this.ledger = ledger;
        this.publications = publications;
    }

    /** Order this before adapter input locks and job/receipt locks. */
    public void lockDomain() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || isolation != null && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw new IllegalStateException("Current state requires a writable READ_COMMITTED transaction");
        }
        members.lockDomain();
    }

    /**
     * Care, runtime and publication writers participate in the domain lock. Adult auth also locks
     * member/session rows. Hold the caller's transaction open through any admission or completion.
     */
    public Snapshot read(Query query) {
        lockDomain();
        UUID child = query.childId();
        Long generation = null;
        if (query.actor().kind() == ActorPrincipal.Kind.CHILD) {
            generation = runtime.sessionAccess(query.actor(), query.slot().sessionId());
            if (!child.toString().equals(query.actor().childId())) throw new ApiException(ErrorCode.CHILD_ACCESS_DENIED);
        } else {
            access.checkInTransaction(query.actor(), child.toString(), query.adultCapability());
            if (query.slot() != null && query.adultCapability() != Capability.VIEW_SUMMARY) {
                access.checkInTransaction(query.actor(), child.toString(), Capability.VIEW_SUMMARY);
            }
        }
        var profile = db.queryForList("""
                select c.guardian_id,c.profile_version from care.child c
                join identity.member g on g.id=c.guardian_id
                where c.id=? and c.deleted_at is null and g.status='ACTIVE'
                  and g.role='GUARDIAN' and g.email_verified_at is not null
                for share of g
                """, child).stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        UUID guardian = (UUID) profile.get("guardian_id");
        UUID invitation = null;
        if (generation == null && !guardian.toString().equals(query.actor().actorId())) {
            invitation = db.queryForObject("""
                    select invitation_id from care.child_therapist
                    where child_id=? and therapist_id=? and state='ACTIVE'
                    """, UUID.class, child, UUID.fromString(query.actor().actorId()));
        }
        var revisions = new EnumMap<ConsentScope, Long>(ConsentScope.class);
        db.query("select scope,revision from care.consent where child_id=? and granted and withdrawn_at is null",
                rs -> {
                    ConsentScope scope = ConsentScope.valueOf(rs.getString("scope"));
                    if (query.purposes().contains(scope) || scope == ConsentScope.CORE_PROFILE_LEARNING) {
                        revisions.put(scope, rs.getLong("revision"));
                    }
                }, child);
        if (!revisions.keySet().containsAll(query.purposes())
                || (generation != null || invitation != null || query.slot() != null
                    || query.adultCapability() == Capability.VIEW_SUMMARY)
                    && !revisions.containsKey(ConsentScope.CORE_PROFILE_LEARNING)) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED);
        }
        return new Snapshot(child, guardian, invitation, generation,
                ((Number) profile.get("profile_version")).longValue(), revisions,
                query.slot() == null ? null : progress(child, query.slot()));
    }

    private Progress progress(UUID child, SessionSlot slot) {
        var state = ledger.session(slot.sessionId());
        if (!child.equals(state.childId())) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        if (state.status() != LearningContracts.SessionStatus.ACTIVE || state.completedCount() + 1 != slot.slot()) {
            throw new ApiException(ErrorCode.STALE_CHECKPOINT);
        }
        runtime.checkpointLive(slot.sessionId());
        var row = db.queryForList("""
                select c.attempt_id,c.revision,c.body::text as checkpoint,pin.plan_hash,ps.publication_id,ps.content_hash
                from learning.runtime_checkpoint c join learning.session_pin pin on pin.session_id=c.session_id
                join learning.plan_slot ps on ps.plan_id=? and ps.slot=c.slot
                join learning.plan p on p.id=ps.plan_id and p.manifest_hash=pin.plan_hash
                where c.session_id=? and c.slot=? and ps.publication_id is not null
                """, state.planId(), slot.sessionId(), slot.slot()).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.STALE_CHECKPOINT));
        var checkpoint = json.readTree((String) row.get("checkpoint"));
        UUID attempt = (UUID) row.get("attempt_id");
        long revision = ((Number) row.get("revision")).longValue();
        if (checkpoint.path("slot").asInt() != slot.slot()
                || !attempt.toString().equals(checkpoint.path("attemptId").asText())
                || checkpoint.path("checkpointRevision").asLong() != revision) {
            throw new ApiException(ErrorCode.STALE_CHECKPOINT);
        }
        if (!row.get("plan_hash").equals(checkpoint.path("manifestHash").asText())) {
            throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
        }
        if (checkpoint.path("worldRevision").asLong(-1) != state.worldRevision()) {
            throw new ApiException(ErrorCode.STALE_WORLD_STATE);
        }
        List<String> choices = checkpoint.path("selectedChoiceIds").valueStream().map(n -> n.asText()).toList();
        String terminal = checkpoint.path("terminalSceneId").asText(null);
        String emotion = checkpoint.path("selectedEmotionId").asText(null);
        var reviewed = publications.execution(state.planId(), slot.slot());
        String target = terminal == null || emotion == null ? null : reviewed.stream()
                .filter(p -> p.choiceIds().equals(choices) && p.terminalSceneId().equals(terminal)
                        && p.emotionIds().contains(emotion))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.INVALID_BRANCH_PATH)).actorId();
        var currentSlot = state.slots().stream().filter(s -> s.slot() == slot.slot()).findFirst().orElseThrow();
        String proof = checkpoint.path("confirmedExpressionRef").asText(null);
        UUID confirmedExpression = null;
        if (proof != null) {
            try {
                confirmedExpression = UUID.fromString(proof);
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(ErrorCode.STALE_CHECKPOINT);
            }
        }
        return new Progress(state.planId(), slot.sessionId(), slot.slot(), attempt,
                state.revision(), state.worldRevision(), revision,
                checkpoint.path("draftVersion").asLong(), confirmedExpression,
                choices, terminal, emotion, target, currentSlot.scenarioVersionId(),
                (UUID) row.get("publication_id"), (String) row.get("plan_hash"), (String) row.get("content_hash"));
    }
}
