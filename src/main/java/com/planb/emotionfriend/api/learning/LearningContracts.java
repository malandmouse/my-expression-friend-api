package com.planb.emotionfriend.api.learning;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.planb.emotionfriend.api.common.validation.SchemaVersion;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;
import java.time.*;
import java.util.*;

public final class LearningContracts {
    private LearningContracts() {}
    public enum Decision { CONFIRM, HOLD }
    public enum MaterialStatus { UNCONFIRMED, CONFIRMED, ON_HOLD }
    public enum SessionStatus { ACTIVE, COMPLETED }

    public record ConfirmationRequest(
            @NotNull @Size(min=1, max=5) @UniqueElements List<@NotNull UUID> summaryIds,
            @NotNull Decision decision) {}
    public record SessionStart(@SchemaVersion String schemaVersion,
            @NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]*") String deviceId) {}
    public record ExpressionRequest(@NotBlank @Size(max=2000) String text,
            @NotNull @Min(1) Long draftVersion,
            @NotNull @Pattern(regexp="TYPED") String inputSource,
            @NotNull @AssertTrue Boolean childConfirmed) {
        @Override public String toString() { return "ExpressionRequest[redacted]"; }
    }
    public record FinalizeRequest(@NotNull UUID confirmedExpressionRef,
            @NotNull @Min(1) Long draftVersion,
            @NotNull @Size(max=100) @UniqueElements List<@NotBlank @Size(max=128) String> selectedChoiceIds,
            @NotBlank @Size(max=128) String terminalSceneId,
            @NotBlank @Size(max=128) String selectedEmotionId,
            @NotNull @Pattern(regexp="ORIGINAL|AI_COMPOSED") String expressionChoice,
            @NotNull @Min(1) Long expectedSessionRevision,
            @NotNull @Min(0) Long expectedWorldRevision,
            @NotBlank @Size(max=128) String clientCommitId) {}

    public record SummaryItem(UUID summaryId, UUID materialId, String subject, String summary, MaterialStatus status) {}
    public record Page<T>(UUID childId, List<T> items, UUID nextCursor) {}
    public record Expression(UUID confirmedExpressionRef, Instant expiresAt, long draftVersion) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SessionSlot(int slot, UUID materialId, UUID scenarioVersionId, boolean completed, UUID receiptId) {}
    public record Session(UUID sessionId, UUID childId, UUID planId, int dayIndex, String executionMode,
            LocalDate localDate, String timezone, SessionStatus status, long revision, long worldRevision,
            int stars, int completedCount, List<SessionSlot> slots) {}
    public record SessionItem(UUID sessionId, UUID planId, int dayIndex, LocalDate localDate,
            String timezone, SessionStatus status, int stars, int completedCount, Instant startedAt, Instant completedAt) {}
    public record Receipt(UUID receiptId, UUID sessionId, UUID planId, int slot, Instant completedAt,
            long worldRevision, int starsAwarded, int stars, int completedCount,
            UUID confirmedExpressionRef, long draftVersion, String expressionChoice, String actorId, String nextAction) {}
    public record Result(UUID receiptId, UUID sessionId, UUID planId, int slot, String subject,
            Instant completedAt, int starsAwarded) {}
    public record LearningSummary(Instant periodStart, Instant periodEnd, long completedLoops, long starsEarned,
            List<MetricCount> recognitionCounts, List<MetricCount> assistanceCounts,
            String metricSnapshotHash, List<String> limitations) {}
    public record MetricCount(String key, long count) {}
}
