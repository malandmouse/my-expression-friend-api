package com.planb.emotionfriend.api.agent;
import com.planb.emotionfriend.api.learning.*;
import com.planb.emotionfriend.api.children.ChildContracts.ConsentScope;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;

class PrototypeFeatureTest {
    final JsonMapper json=new JsonMapper();
    @Test void expandedGptCatalogKeepsTheOriginalCatalogAndRejectsUnknownVersions() {
        var binding=new PrototypeBinding(json,new PrototypeContracts());
        var old=binding.cuesForConfiguration("prototype-st-v2.1-ko3");var current=binding.cuesForConfiguration("prototype-st-v2.1-ko4");
        assertThat(old.path("animationCues").size()).isEqualTo(2);
        assertThat(current.path("animationCues").size()).isEqualTo(18);
        assertThat(current.path("faceCues").size()).isEqualTo(16);
        assertThat(binding.installedCues(old.path("catalogId").asText())).isEqualTo(old);
        assertThat(binding.installedCues(current.path("catalogId").asText())).isEqualTo(current);
        assertThatThrownBy(()->binding.installedCues("invented-catalog")).isInstanceOf(RuntimeException.class);
    }
    @Test void p1CanonicalMatchesEcmascriptForKoreanKeysEscapesAndIntensity() {
        var n=json.readTree("{\"z\":0.3500,\"나\":\"안녕\\n친구\",\"a\":[1,true,null]}");
        assertThat(PrototypeJson.canonical(n)).isEqualTo("{\"a\":[1,true,null],\"z\":0.35,\"나\":\"안녕\\n친구\"}");
        assertThatThrownBy(()->PrototypeJson.canonical(json.readTree("9007199254740992"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(PrototypeJson.canonical(json.readTree("[0.75,1,1.25]"))).isEqualTo("[0.75,1,1.25]");
        assertThatThrownBy(()->PrototypeJson.canonical(json.readTree("0.000001"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sentenceSplitPreservesEveryApprovedCharacterAndWhitespace() {
        String original="오늘은 좋았어.  깜짝 놀랐지?\n그래서 말하고 싶었어!";
        var parts=PrototypeReleaseService.sentences(original);
        assertThat(parts).hasSize(3);assertThat(String.join("",parts)).isEqualTo(original);
        assertThat(parts.get(0)).isEqualTo("오늘은 좋았어.  ");
    }
    @Test void frozenSettingsDoNotIgnoreConsentOrRelationshipRevocation() {
        UUID child=UUID.randomUUID(),guardian=UUID.randomUUID();
        var a=new LearningCurrent.Snapshot(child,guardian,null,null,1,Map.of(ConsentScope.CORE_PROFILE_LEARNING,1L),null);
        var edited=new LearningCurrent.Snapshot(child,guardian,null,null,2,a.consentRevisions(),null);
        var revoked=new LearningCurrent.Snapshot(child,guardian,null,null,2,Map.of(ConsentScope.CORE_PROFILE_LEARNING,2L),null);
        assertThat(AgentJobService.sameAuthority(a,edited)).isTrue();assertThat(AgentJobService.sameAuthority(a,revoked)).isFalse();
    }
    @Test void statefulMockEnvelopeIsCompatibleWithBackendAdmissionAndFiveCoreGraphs() throws Exception {
        try(var in=getClass().getResourceAsStream("/agent-v21.mock.json")) {
            assertThat(in).isNotNull();var envelope=json.readTree(in);new AgentEnvelopeValidator().validate(envelope.toString());
            assertThat(envelope.path("paidCalls").asInt()).isZero();assertThat(envelope.path("coreDrafts").size()).isEqualTo(5);
            var structure=new CoreScenarioValidator();
            for(var draft:envelope.path("coreDrafts")) {
                assertThat(structure.validate(draft.path("payload").toString(),CoreScenarioValidator.Profile.GENERATED_1_TO_3).report().pathCount()).isEqualTo(4);
                for(var q:draft.path("payload").path("emotionQuestionByTerminal")){assertThat(q.path("options").size()).isEqualTo(5);assertThat(q.path("options").valueStream().anyMatch(o->o.path("emotionId").asText().equals("UNKNOWN"))).isTrue();}
            }
        }
    }
}
