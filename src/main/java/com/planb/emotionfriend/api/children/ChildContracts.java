package com.planb.emotionfriend.api.children;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.planb.emotionfriend.api.common.validation.SchemaVersion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.UUID;

public final class ChildContracts {
    private ChildContracts() {}

    public enum Gender {
        BOY,
        GIRL
    }

    public enum AgeBand {
        ELEMENTARY_MIDDLE,
        TARGET_8_TO_13
    }

    public enum ReceptiveSupport {
        UNKNOWN
    }

    public enum RecognitionSupport {
        UNKNOWN,
        WITH_PICTURES
    }

    public enum ExpressiveSupport {
        UNKNOWN,
        SHORT_SENTENCES
    }

    public enum CommunicationMode {
        UNKNOWN,
        SPEECH,
        TEXT_EDIT,
        EXPRESSION_CARDS
    }

    public enum SupportPreference {
        UNKNOWN,
        DEFAULT
    }

    public enum ConsentScope {
        CORE_PROFILE_LEARNING,
        SPEECH_STT,
        CARD_EXTERNAL_PROCESSING,
        CAMERA_LOCAL,
        OPTIONAL_RESEARCH_OR_MODEL_TRAINING,
        LOCAL_DRAFT_RESUME
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileInput(
            @SchemaVersion String schemaVersion,
            @NotNull Gender gender,
            @NotNull AgeBand ageBand,
            @NotNull @Pattern(regexp = "ko") String primaryLanguage,
            @NotNull ReceptiveSupport receptiveSupport,
            @NotNull RecognitionSupport recognitionSupport,
            @NotNull ExpressiveSupport expressiveSupport,
            @NotNull @Size(min = 1, max = 4) @UniqueElements
                    List<@NotNull CommunicationMode> communicationModes,
            @NotNull @Size(min = 1, max = 2) @UniqueElements
                    List<@NotNull SupportPreference> supportPreferences,
            @Size(max = 20) @UniqueElements List<@NotBlank @Size(max = 256) String> interests,
            @Size(max = 20) @UniqueElements
                    List<@NotBlank @Size(max = 256) String> preferredActivities,
            @Size(max = 20) @UniqueElements
                    List<@NotBlank @Size(max = 256) String> sensoryPreferences,
            @Size(max = 20) @UniqueElements List<@NotBlank @Size(max = 256) String> avoidTopics,
            @Size(min = 1, max = 256) String recentExperienceRef) {
        @Override
        public String toString() {
            return "ProfileInput[redacted]";
        }
    }

    public record CreateChildRequest(
            @SchemaVersion String schemaVersion,
            @NotBlank @Size(max = 40) String pseudonym,
            @NotNull @Valid ProfileInput profile,
            @NotNull @AssertTrue Boolean guardianRelationshipConfirmed) {
        @Override
        public String toString() {
            return "CreateChildRequest[redacted]";
        }
    }

    public record UpdateProfileRequest(
            @Min(1) long expectedProfileVersion,
            @NotBlank @Size(max = 40) String pseudonym,
            @NotNull @Valid ProfileInput profile) {
        @Override
        public String toString() {
            return "UpdateProfileRequest[redacted]";
        }
    }

    public record ConsentChangeRequest(
            @SchemaVersion String schemaVersion,
            @NotNull ConsentScope scope,
            @NotNull Boolean granted,
            @NotBlank @Size(max = 80) String noticeVersion,
            @NotNull UUID guardianVerificationRef) {}

    public record InvitationRequest(@NotBlank @Email @Size(max = 254) String therapistEmail) {
        @Override
        public String toString() {
            return "InvitationRequest[redacted]";
        }
    }

    public record AcceptInvitationRequest(
            @NotNull @AssertTrue Boolean treatmentRelationshipConfirmed) {}
}
