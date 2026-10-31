package com.planb.emotionfriend.api.children;

import static com.planb.emotionfriend.api.children.ChildContracts.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.planb.emotionfriend.api.common.security.ChildAccessChecker.Capability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire response schemas for the stored/replayed JSON responses. */
public final class ChildResponses {
    private ChildResponses() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileVersion(
            String schemaVersion,
            Gender gender,
            AgeBand ageBand,
            String primaryLanguage,
            ReceptiveSupport receptiveSupport,
            RecognitionSupport recognitionSupport,
            ExpressiveSupport expressiveSupport,
            List<CommunicationMode> communicationModes,
            List<SupportPreference> supportPreferences,
            List<String> interests,
            List<String> preferredActivities,
            List<String> sensoryPreferences,
            List<String> avoidTopics,
            String recentExperienceRef,
            UUID childId,
            long profileVersion,
            Instant createdAt) {}

    public record Summary(
            UUID childId, String pseudonym, long profileVersion, List<Capability> capabilities) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Children(List<Summary> items, UUID nextCursor) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Consent(
            UUID consentId,
            UUID childId,
            ConsentScope scope,
            boolean granted,
            String noticeVersion,
            UUID guardianVerificationRef,
            Instant recordedAt,
            Instant withdrawnAt,
            long revision) {}

    public record Consents(List<Consent> items) {}

    public record SentInvitation(
            UUID invitationId,
            String therapistEmail,
            String state,
            Instant createdAt,
            Instant expiresAt) {}

    public record SentInvitations(List<SentInvitation> items) {}

    public record InboxInvitation(
            UUID invitationId,
            String guardianDisplayName,
            String guardianContact,
            Instant createdAt,
            Instant expiresAt,
            List<Capability> capabilities) {}

    public record Inbox(List<InboxInvitation> items) {}

    public record Therapist(
            UUID therapistId,
            String displayName,
            Instant acceptedAt,
            List<Capability> capabilities) {}

    public record Therapists(List<Therapist> items) {}
}
