package com.planb.emotionfriend.api.learning;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import com.networknt.schema.dialect.Dialects;
import com.planb.emotionfriend.api.common.error.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic manuscript admission only. No media, semantic review or publication approval. */
@Component
public final class CoreScenarioValidator {
    public enum Profile { CORE_0_3, GENERATED_1_TO_3 }
    public static final String VERSION="core-scenario-structure-v1";
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Schema schema;

    public CoreScenarioValidator() {
        try(var in=getClass().getResourceAsStream("/contracts/core-scenario-0.3.0.schema.json")) {
            schema=SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(json.readTree(in));
        } catch(Exception e) { throw new IllegalStateException("Core manuscript schema unavailable",e); }
    }
    public record Report(String validatorVersion, Profile profile, String validatedHash,
            String structuralStatus, int pathCount, String dependenciesStatus, String publicationStatus) {}
    public record Validated(String payload, String payloadHash, String sourceScenarioVersionId,
            String sourceContentHash, Report report) {
        @Override public String toString() { return "Validated[manuscript redacted]"; }
    }
    public Validated validate(String payload,Profile profile) {
        if(payload==null || profile==null || payload.getBytes(StandardCharsets.UTF_8).length>65536) throw invalid();
        try {
            JsonNode n=json.readTree(payload);
            if(n==null || !schema.validate(n,ctx->ctx.executionConfig(c->c.formatAssertionsEnabled(true))).isEmpty()) throw invalid();
            // Stored lifecycle is ours: importing an APPROVED/PUBLISHED assertion never grants approval.
            if(!"DRAFT".equals(n.path("status").asText())) throw invalid();
            var scenes=index(n.path("sceneGraph").path("scenes"),"sceneId");
            var questions=index(n.path("emotionQuestionByTerminal"),"terminalSceneId");
            var assets=strings(n.path("assetRefs"));
            for(var scene:scenes.values()) {
                check(Set.of("jun","nara").contains(scene.path("actorId").asText()));
                check(assets.contains(scene.path("voiceRef").asText()));
                if(scene.has("choices")) index(scene.path("choices"),"choiceId");
            }
            for(var q:questions.values()) {
                check(Set.of("jun","nara").contains(q.path("targetActorId").asText()));
                var options=index(q.path("options"),"emotionId");
                check(options.keySet().containsAll(strings(q.path("acceptedEmotionIds"))));
            }
            var rules=index(n.path("characterReplyPolicy").path("rules"),"ruleId");
            check(rules.values().stream().filter(r->"UNKNOWN".equals(r.path("whenFunction").asText())).count()==1);
            for(var rule:rules.values()) check(assets.contains(rule.path("voiceRef").asText()));
            for(var replay:n.path("adaptiveSupport").path("replaySceneIds")) check(scenes.containsKey(replay.asText()));
            var visited=new HashSet<String>();var terminals=new HashSet<String>();int[] paths={0};
            walk(n.path("sceneGraph").path("startSceneId").asText(),scenes,questions,new HashSet<>(),visited,terminals,0,paths,profile);
            check(visited.equals(scenes.keySet()) && terminals.equals(questions.keySet()));
            String canonical=json.writeValueAsString(canonical(n));
            String hash=LearningCatalog.hash(canonical);
            return new Validated(canonical,hash,n.path("scenarioVersionId").asText(),n.path("contentHash").asText(),
                    new Report(VERSION,profile,hash,"PASS",paths[0],"NOT_CHECKED","NOT_APPROVED"));
        } catch(ApiException e) { throw e; }
        catch(Exception e) { throw invalid(); }
    }
    private void walk(String id,Map<String,JsonNode> scenes,Map<String,JsonNode> questions,
            Set<String> path,Set<String> visited,Set<String> terminals,int turns,int[] paths,Profile profile) {
        check(scenes.containsKey(id) && !path.contains(id));
        var next=new HashSet<>(path);next.add(id);visited.add(id);var s=scenes.get(id);
        if(s.path("end").asBoolean()) {
            check(++paths[0]<=256 && questions.containsKey(id));
            if(profile==Profile.GENERATED_1_TO_3) check(turns>=1 && turns<=3);
            terminals.add(id);
            for(var evidence:questions.get(id).path("evidenceSceneIds")) check(next.contains(evidence.asText()));
        } else if(s.has("nextSceneId")) {
            walk(s.path("nextSceneId").asText(),scenes,questions,next,visited,terminals,turns,paths,profile);
        } else {
            for(var c:s.path("choices")) walk(c.path("nextSceneId").asText(),scenes,questions,next,visited,terminals,turns+1,paths,profile);
        }
    }
    private static Map<String,JsonNode> index(JsonNode nodes,String key) {
        var result=new LinkedHashMap<String,JsonNode>();
        for(var n:nodes) check(result.putIfAbsent(n.path(key).asText(),n)==null);
        return result;
    }
    private static Set<String> strings(JsonNode nodes) {
        var result=new HashSet<String>();for(var n:nodes) result.add(n.asText());return result;
    }
    private JsonNode canonical(JsonNode n) {
        if(n.isObject()) {
            ObjectNode result=json.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);
            for(String k:keys) result.set(k,canonical(n.get(k)));return result;
        }
        if(n.isArray()) { var result=json.createArrayNode();for(var x:n) result.add(canonical(x));return result; }
        return n;
    }
    private static void check(boolean valid) { if(!valid) throw invalid(); }
    private static ApiException invalid() { return new ApiException(ErrorCode.INVALID_SCHEMA); }
}
