package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.common.validation.SchemaVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;
import java.time.Instant;
import java.util.*;

/** Narrow privacy-preserving runtime adapter; explicit wire differences are documented locally. */
public final class RuntimeContracts {
    private RuntimeContracts() {}
    public record DeviceRequest(@SchemaVersion String schemaVersion,@NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]*") String deviceId) {}
    public record Exchange(@NotBlank @Size(max=256) String oneTimeCode,@NotBlank @Size(max=128) String deviceId) {
        @Override public String toString(){return "Exchange[redacted]";}
    }
    public record Refresh(@NotBlank @Size(max=256) String refreshToken,@NotBlank @Size(max=128) String deviceId) {
        @Override public String toString(){return "Refresh[redacted]";}
    }
    public record Grant(UUID grantId,UUID childId,String deviceId,String oneTimeCode,Instant expiresAt) {
        @Override public String toString(){return "Grant[redacted]";}
    }
    public record Token(String accessToken,Instant expiresAt) {@Override public String toString(){return "Token[redacted]";}}
    public record Credential(Token credential,UUID childId,UUID planId,String deviceId,long generation) {
        @Override public String toString(){return "Credential[redacted]";}
    }
    public record Persistent(Credential runtimeCredential,String refreshToken,Instant refreshExpiresAt) {
        @Override public String toString(){return "Persistent[redacted]";}
    }
    public record Entry(@SchemaVersion String schemaVersion,@NotNull @Pattern(regexp="HOME|MISSION_MENU") String entryPoint,
            @NotNull UUID eventId,@NotBlank @Size(max=128) String deviceId) {}
    public record Start(@SchemaVersion String schemaVersion,@NotNull @Pattern(regexp="REGULAR") String executionMode,
            @NotBlank @Size(max=128) String deviceId) {}
    public record Checkpoint(@NotNull @Min(1) Long checkpointRevision,@NotNull @Min(1) @Max(5) Integer slot,
            @NotNull UUID attemptId,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String manifestHash,
            @NotNull @Min(0) Long worldRevision,@NotNull @Size(max=100) @UniqueElements List<@NotBlank @Size(max=128) String> selectedChoiceIds,
            @Size(max=128) String sceneId,@Size(max=128) String terminalSceneId,@Size(max=128) String selectedEmotionId,
            @NotNull @Min(1) Long draftVersion,UUID confirmedExpressionRef,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Valid PresentationCursor presentationCursor) {
        public Checkpoint(Long revision,Integer slot,UUID attempt,String manifest,Long world,List<String> choices,String scene,String terminal,String emotion,Long draft,UUID expression) {
            this(revision,slot,attempt,manifest,world,choices,scene,terminal,emotion,draft,expression,null);
        }
    }
    public record PresentationCursor(@NotBlank @Size(max=128) String sceneId,@Min(0) @Max(9) int sentenceIndex) {}
    public record Commit(@NotNull UUID attemptId,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String manifestHash,
            @NotNull UUID scenarioVersionId,@NotNull @Min(1) Long expectedCheckpointRevision,
            @NotNull @Valid LearningContracts.FinalizeRequest completion) {}
    public record LeaseRequest(@NotNull @Min(1) @Max(5) Integer slot,@NotNull UUID assetId,
            @NotBlank @Pattern(regexp="[0-9a-f]{64}") String contentHash) {}
    public record Lease(UUID leaseId,Instant expiresAt,String contentHash) {}
    public record CheckpointPlayback(@NotNull @Valid Checkpoint checkpoint,@NotNull @Valid LeaseRequest playback) {}
    public record Advanced(tools.jackson.databind.JsonNode checkpoint,Lease playbackLease) {}
    public record Pin(UUID sessionId,String planManifestHash,long entryWorldRevision,tools.jackson.databind.JsonNode planManifest) {}
    public record Mission(UUID planId,int dayIndex,String timezone,java.time.LocalDate unlockedDate) {}
    public record Home(UUID childId,Instant serverNow,List<Mission> plans) {}
    public record Started(LearningContracts.Session session,Credential runtimeCredential,Pin pin,tools.jackson.databind.JsonNode checkpoint) {
        @Override public String toString(){return "Started[credential redacted]";}
    }
    public record Restored(LearningContracts.Session session,Pin pin,tools.jackson.databind.JsonNode checkpoint,String expressionRecovery) {}
}
