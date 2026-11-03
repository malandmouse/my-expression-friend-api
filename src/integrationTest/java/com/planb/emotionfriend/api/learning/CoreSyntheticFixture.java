package com.planb.emotionfriend.api.learning;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed synthetic bytes are deliberately not playable media or real-device evidence. */
final class CoreSyntheticFixture {
    final JsonMapper json=new JsonMapper();final CoreDependencyValidator hashes=new CoreDependencyValidator();
    final Map<String,CoreDependencyValidator.Artifact> artifacts=new TreeMap<>();
    final Map<String,String> cues=new TreeMap<>();final ObjectNode scenario;final ObjectNode catalog;
    CoreSyntheticFixture() throws Exception {
        try(var in=getClass().getResourceAsStream("/core/scenario.json")){scenario=(ObjectNode)json.readTree(in);}
        for(var ref:scenario.path("assetRefs")) {
            String id=ref.asText();byte[] bytes=("SYNTHETIC_NOT_PLAYABLE:"+id).getBytes(StandardCharsets.UTF_8);
            artifacts.put(id,new CoreDependencyValidator.Artifact(uuid(id),"MEDIA",id,bytes,CoreDependencyValidator.sha(bytes)));
        }
        var policy=json.createObjectNode().put("policyVersionId","policy-ko-h02-r1").put("status","DRAFT").put("generatorVersion","synthetic-v1").put("validatorVersion","synthetic-v1")
                .put("fallback","KEEP_ORIGINAL_OR_RERECORD").put("runtimeCardsIndividuallyPreapproved",false);
        policy.putArray("allowedCategories").add("EMOTION");
        policy.putObject("generationConstraints").put("language","ko").put("maxCards",8).put("maxEojeolPerCard",5).put("maxRegenerations",1)
                .put("preserveOriginalIntentByDefault",true).put("requireChildSelectionForNewEmotionOrReason",true).put("noUnconfirmedPastEvents",true);
        policy.put("policyHash",hashes.digest(CoreDependencyValidator.project(policy,"policyHash","status")));
        scenario.withObject("cardPolicyRef").put("policyHash",policy.path("policyHash").asText());put("POLICY","policy-ko-h02-r1",policy);
        for(String actor:List.of("jun","nara")) {
            String id="persona-"+actor+"-h02-r1";var p=json.createObjectNode().put("personaVersionId",id).put("actorId",actor).put("approvalStatus","DRAFT");p.putArray("traits").add("합성 고정 인물");
            p.put("contentHash",hashes.digest(CoreDependencyValidator.project(p,"contentHash","status")));put("PERSONA",id,p);
        }
        cues.put("jun:EXPRESSION:CURIOUS","expression-jun-curious-h02-r1");cues.put("nara:EXPRESSION:HAPPY","expression-nara-happy-h02-r1");
        cues.put("jun:ANIMATION:ASK","motion-jun-ask-h02-r1");cues.put("jun:ANIMATION:NOD","motion-jun-nod-h02-r1");cues.put("nara:ANIMATION:NOD","motion-nara-nod-h02-r1");
        cues.put("jun:MODEL:JungHoon","model-jun-junghoon-h02-r1");cues.put("nara:MODEL:MainPlayer","model-nara-mainplayer-h02-r1");
        catalog=json.createObjectNode().put("catalogId","synthetic-catalog-v1").put("revision",1).put("approvalRef","synthetic-only");var actors=catalog.putObject("actors");
        for(String actor:List.of("jun","nara")) {
            String voice=actor.equals("jun")?"voice-d1-s1-prepare-ask-h02-r1":"voice-d1-s1-prepare-end-basket-h02-r1";
            var a=actors.putObject(actor).put("actorId",actor).put("personaRef","persona-"+actor+"-h02-r1").put("personaRevision",1)
                .put("modelId",actor.equals("jun")?"JungHoon":"MainPlayer");
            a.put("modelHash",artifacts.get(cues.get(actor+":MODEL:"+a.path("modelId").asText())).bytesHash());
            for(var e:Map.of("voice",voice,"lip",voice.replace("voice-","lip-"),"motion","motion-"+actor+"-nod-h02-r1").entrySet())
                a.put(e.getKey()+"Ref",e.getValue()).put(e.getKey()+"Hash",artifacts.get(e.getValue()).bytesHash());
        }
        var recipes=catalog.putArray("recipes");
        for(var scene:scenario.path("sceneGraph").path("scenes")) {
            String actor=scene.path("actorId").asText(),voice=scene.path("voiceRef").asText();
            var r=recipes.addObject().put("recipeId","synthetic-"+scene.path("sceneId").asText()).put("recipeRevision",1).put("actorId",actor).put("cue",scene.path("animationCue").asText())
                .put("voiceRef",voice).put("lipRef",voice.replace("voice-","lip-")).put("motionRef",cues.get(actor+":ANIMATION:"+scene.path("animationCue").asText())).put("contentRevision",1).put("approvedAt","2026-11-02T00:00:00Z");
            String expression=cues.get(actor+":EXPRESSION:"+scene.path("expressionCue").asText());
            r.putArray("propRefs").addObject().put("assetId",expression).put("revision",1).put("hash",artifacts.get(expression).bytesHash());
            var refs=new TreeSet<>(List.of(voice,r.path("lipRef").asText(),r.path("motionRef").asText(),expression));
            var env=json.createObjectNode();env.set("content",CoreDependencyValidator.project(r,"contentHash","approvedAt"));env.putObject("bindings").set("assets",mediaRefs(refs));r.put("contentHash",hashes.digest(env));
        }
        catalog.put("contentHash",hashes.digest(CoreDependencyValidator.project(catalog,"contentHash","approvalRef")));put("CATALOG","synthetic-catalog-v1",catalog);
        sourceHash(scenario);
    }
    String manuscript(int day,int slot) {var s=scenario.deepCopy();s.put("scenarioVersionId","synthetic-day-"+day+"-slot-"+slot);sourceHash(s);return hashes.canonical(s).toString();}
    private void sourceHash(ObjectNode s) {
        var env=json.createObjectNode();env.set("content",CoreDependencyValidator.project(s,"contentHash","status"));var b=env.putObject("bindings");
        var ps=b.putArray("personas");for(String id:CoreDependencyValidator.strings(s.path("personaRefs")))ps.addObject().put("personaVersionId",id).put("contentHash",hashes.parse(artifacts.get(id).bytes()).path("contentHash").asText());
        b.set("policy",s.path("cardPolicyRef"));b.set("assets",mediaRefs(CoreDependencyValidator.strings(s.path("assetRefs"))));
        var c=b.putObject("catalog");for(String k:List.of("catalogId","revision","contentHash"))c.set(k,catalog.path(k));s.put("contentHash",hashes.digest(env));
    }
    private ArrayNode mediaRefs(Collection<String> ids) {var out=json.createArrayNode();for(String id:new TreeSet<>(ids))out.addObject().put("assetId",id).put("sha256",artifacts.get(id).bytesHash());return out;}
    private void put(String kind,String id,JsonNode node) {byte[] bytes=hashes.canonical(node).toString().getBytes(StandardCharsets.UTF_8);artifacts.put(id,new CoreDependencyValidator.Artifact(uuid(id),kind,id,bytes,CoreDependencyValidator.sha(bytes)));}
    static UUID uuid(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
}
