package com.planb.emotionfriend.api.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

/** Only caregiver-supplied settings; child ID/profile/consent and execution state come from foundation. */
public final class AgentContracts {
    private AgentContracts() {}
    public record Event(@NotNull LocalDate occurredOn,@NotBlank @Size(max=500) String text,
                        @Size(max=120) String reportedFeeling) {
        @Override public String toString(){return "Event[redacted]";}
    }
    public record Generate(@Min(1) @Max(30) int programDay,@NotNull LocalDate localDate,
            @NotNull @Pattern(regexp="JUN|NARA|BOTH") String characterPreference,
            @NotNull @Size(min=1,max=2) @org.hibernate.validator.constraints.UniqueElements List<@NotNull @Pattern(regexp="EMOTION_CONTEXT|EMOTION_CAUSE|DIFFERENT_FEELINGS|SIMILAR_EMOTIONS|SELF_FEELING|SELF_CAUSE|INTENSITY_MIXED|THOUGHT_WISH") String> priorityGoals,
            @NotNull @Size(max=5) List<@NotNull @Valid Event> dailyEvents) {
        @Override public String toString(){return "Generate[redacted]";}
    }
    public record Cancel(@Min(1) long expectedVersion) {}
}
