package com.planb.emotionfriend.api.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.planb.emotionfriend.api.learning.CoreScenarioValidator;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentMigrationTest {
    @Test void missingProfileSupportIsNotInventedAndReportsBelongToChildResponses() {
        var json=new JsonMapper();var profile=json.createObjectNode();profile.put("pseudonym","합성 아동");
        profile.putObject("profile").put("ageBand","TARGET_8_TO_13").put("primaryLanguage","ko");
        var request=new AgentContracts.Generate(1,LocalDate.of(2026,11,5),"BOTH",List.of("EMOTION_CONTEXT","SELF_FEELING"),
            List.of(new AgentContracts.Event(LocalDate.of(2026,11,4),"허구의 최근 경험",null)));
        var input=FoundationProfileMapper.input(json,UUID.randomUUID(),UUID.randomUUID(),3,2,profile,request);
        assertTrue(input.path("childProfile").path("experienceNarrative").isNull());
        assertTrue(input.path("childProfile").path("understanding").path("informationAmount").isNull());
        assertEquals("CHILD_REPLY_CANDIDATE_ONLY",input.path("dailyEvents").get(0).path("allowedUse").asText());
        assertTrue(input.path("continuity").path("relationships").isEmpty());
        assertEquals(5,input.path("program").path("sessionsPerDay").asInt());
    }
    @Test void fiveWorkerDraftsMatchFoundationStructuralProfileWithoutPretendingToBePublished() throws Exception {
        var json=new JsonMapper();try(var stream=getClass().getResourceAsStream("/core/generated-st-day.json")) {
            var result=json.readTree(stream);assertEquals(5,result.path("coreDrafts").size());
            var validator=new CoreScenarioValidator();
            for(var draft:result.path("coreDrafts")) {
                var validated=validator.validate(draft.path("payload").toString(),CoreScenarioValidator.Profile.GENERATED_1_TO_3);
                assertEquals("NOT_APPROVED",validated.report().publicationStatus());
                assertEquals("NOT_CHECKED",validated.report().dependenciesStatus());
            }
        }
    }
    @Test void workerCannotUseRemoteHttpOrUrlCredentials() {
        var json=new JsonMapper();
        assertThrows(IllegalArgumentException.class,()->new AgentWorkerClient(json,"http://remote.example","",false));
        assertThrows(IllegalArgumentException.class,()->new AgentWorkerClient(json,"https://user:password@example.com","",false));
        assertDoesNotThrow(()->new AgentWorkerClient(json,"http://127.0.0.1:5198","",false));
    }
}
