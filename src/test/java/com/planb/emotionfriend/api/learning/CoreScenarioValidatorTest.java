package com.planb.emotionfriend.api.learning;

import static org.assertj.core.api.Assertions.*;
import com.planb.emotionfriend.api.common.error.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;

class CoreScenarioValidatorTest {
    final JsonMapper mapper=JsonMapper.builder().build();
    final CoreScenarioValidator validator=new CoreScenarioValidator();
    ObjectNode fixture() throws Exception {
        try(var in=getClass().getResourceAsStream("/core/scenario.json")) {
            return (ObjectNode)mapper.readTree(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test void fullCoreDraftPassesStructureOnlyAndCanonicalHashIgnoresKeyOrder() throws Exception {
        var node=fixture();
        var result=validator.validate(node.toString(),CoreScenarioValidator.Profile.GENERATED_1_TO_3);
        var reordered=mapper.createObjectNode();
        node.propertyNames().stream().sorted(java.util.Comparator.reverseOrder()).forEach(k->reordered.set(k,node.get(k)));
        assertThat(validator.validate(reordered.toString(),CoreScenarioValidator.Profile.GENERATED_1_TO_3).payloadHash()).isEqualTo(result.payloadHash());
        assertThat(result.report().structuralStatus()).isEqualTo("PASS");
        assertThat(result.report().dependenciesStatus()).isEqualTo("NOT_CHECKED");
        assertThat(result.report().publicationStatus()).isEqualTo("NOT_APPROVED");
        assertThat(result.report().pathCount()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"cycle","dangling","unreachable","duplicateScene","duplicateChoice","missingQuestion","offPathEvidence","unknownActor","missingVoice","badEmotion","badReplay","extraProperty","wrongVersion","wrongType"})
    void rejectsInvalidContractOrGraph(String mutation) throws Exception {
        var n=fixture(); var scenes=n.path("sceneGraph").withArray("scenes");
        var first=(ObjectNode)scenes.get(0); var choice=(ObjectNode)first.path("choices").get(0);
        var q=(ObjectNode)n.path("emotionQuestionByTerminal").get(0);
        switch(mutation) {
            case "cycle" -> choice.put("nextSceneId","ask");
            case "dangling" -> choice.put("nextSceneId","missing");
            case "unreachable" -> {var extra=scenes.get(1).deepCopy();((ObjectNode)extra).put("sceneId","orphan");scenes.add(extra);}
            case "duplicateScene" -> scenes.add(scenes.get(1).deepCopy());
            case "duplicateChoice" -> first.withArray("choices").add(choice.deepCopy());
            case "missingQuestion" -> n.withArray("emotionQuestionByTerminal").remove(0);
            case "offPathEvidence" -> q.withArray("evidenceSceneIds").add("end-basket");
            case "unknownActor" -> first.put("actorId","stranger");
            case "missingVoice" -> first.put("voiceRef","missing");
            case "badEmotion" -> q.withArray("acceptedEmotionIds").add("MISSING");
            case "badReplay" -> ((ObjectNode)n.path("adaptiveSupport")).withArray("replaySceneIds").add("missing");
            case "extraProperty" -> first.put("approved",true);
            case "wrongVersion" -> n.put("schemaVersion","0.2.0");
            case "wrongType" -> first.put("end","true");
        }
        assertThatThrownBy(()->validator.validate(n.toString(),CoreScenarioValidator.Profile.GENERATED_1_TO_3))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.INVALID_SCHEMA));
    }
    @Test void rejectsDuplicateJsonKeysTrailingContentAndOversizedPayload() throws Exception {
        for(String input:java.util.List.of("{\"schemaVersion\":\"0.3.0\",\"schemaVersion\":\"0.2.0\"}",fixture()+" {}"," ".repeat(65537)))
            assertThatThrownBy(()->validator.validate(input,CoreScenarioValidator.Profile.CORE_0_3)).isInstanceOf(ApiException.class);
    }
    @ParameterizedTest @ValueSource(ints={0,1,3,4})
    void generatedTurnBoundsDoNotRelaxOrReplaceTheCoreProfile(int turns) throws Exception {
        var n=fixture();var template=(ObjectNode)n.path("sceneGraph").path("scenes").get(0).deepCopy();
        var terminal=n.path("sceneGraph").path("scenes").get(1).deepCopy();
        var graph=(ObjectNode)n.path("sceneGraph");var scenes=graph.putArray("scenes");
        graph.put("startSceneId",turns==0?"end-mat":"turn-0");
        for(int i=0;i<turns;i++) {
            var scene=template.deepCopy();scene.put("sceneId","turn-"+i);
            scene.putArray("choices").addObject().put("choiceId","choice-"+i).put("label","Synthetic choice")
                    .put("nextSceneId",i+1==turns?"end-mat":"turn-"+(i+1)).putArray("effects");
            scenes.add(scene);
        }
        scenes.add(terminal);
        n.withArray("emotionQuestionByTerminal").remove(1);
        ((ObjectNode)n.path("adaptiveSupport")).putArray("replaySceneIds").add("end-mat");
        assertThat(validator.validate(n.toString(),CoreScenarioValidator.Profile.CORE_0_3).report().pathCount()).isEqualTo(1);
        if(turns==1 || turns==3)
            assertThat(validator.validate(n.toString(),CoreScenarioValidator.Profile.GENERATED_1_TO_3).report().pathCount()).isEqualTo(1);
        else assertThatThrownBy(()->validator.validate(n.toString(),CoreScenarioValidator.Profile.GENERATED_1_TO_3)).isInstanceOf(ApiException.class);
    }
}
