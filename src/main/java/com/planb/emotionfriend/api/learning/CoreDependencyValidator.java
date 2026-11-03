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
import java.security.MessageDigest;
import java.util.*;

/** Recomputes the Core source envelope AND exact artifact byte hashes. Semantic review is separate. */
@Component
public class CoreDependencyValidator {
    public static final String VERSION="core-dependencies-bytes-v1";
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Map<String,Schema> schemas=new HashMap<>();
    public CoreDependencyValidator() {
        for(String type:List.of("CardGenerationPolicy","ContextPersona","PerformanceCatalog"))
            try(var in=getClass().getResourceAsStream("/contracts/core-"+type+"-0.3.0.schema.json")) {
                schemas.put(type,SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(json.readTree(in)));
            } catch(Exception e) { throw new IllegalStateException("Missing artifact schema",e); }
    }
    public record Artifact(UUID id,String kind,String sourceId,byte[] bytes,String bytesHash) {
        public Artifact { bytes=bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "Artifact[bytes redacted]"; }
    }
    public record Result(String manifest,String manifestHash,List<LearningCatalog.ReviewedPath> paths) {}
    public JsonNode parse(byte[] bytes) {
        try { return json.readTree(bytes); } catch(Exception e) { throw new ApiException(ErrorCode.INVALID_SCHEMA); }
    }
    public void artifact(Artifact a) {
        check(a!=null && a.id()!=null && a.kind()!=null && LearningCatalog.text(a.sourceId(),256) && a.bytes().length>0
                && a.bytes().length<=10*1024*1024 && sha(a.bytes()).equals(a.bytesHash()));
        if(a.kind().equals("MEDIA")) return;
        String type=switch(a.kind()) {case "POLICY"->"CardGenerationPolicy";case "PERSONA"->"ContextPersona";
            case "CATALOG"->"PerformanceCatalog";default->throw new ApiException(ErrorCode.INVALID_SCHEMA);};
        var node=parse(a.bytes());check(schemas.get(type).validate(node,ctx->ctx.executionConfig(c->c.formatAssertionsEnabled(true))).isEmpty());
        String id=switch(a.kind()) {case "POLICY"->"policyVersionId";case "PERSONA"->"personaVersionId";default->"catalogId";};
        check(a.sourceId().equals(node.path(id).asText()));
        if(a.kind().equals("POLICY")) check(digest(project(node,"policyHash","status")).equals(node.path("policyHash").asText()));
        if(a.kind().equals("PERSONA")) check(digest(project(node,"contentHash","status")).equals(node.path("contentHash").asText()));
        if(a.kind().equals("CATALOG")) check(digest(project(node,"contentHash","approvalRef")).equals(node.path("contentHash").asText()));
    }
    public Result validate(String manuscript,List<Artifact> artifacts,Map<String,String> cueBindings) {
        var s=parse(manuscript.getBytes(StandardCharsets.UTF_8));
        var bySource=new HashMap<String,Artifact>();
        for(var a:artifacts) { artifact(a);check(bySource.putIfAbsent(a.sourceId(),a)==null); }
        var used=new HashSet<String>();
        var policyRef=s.path("cardPolicyRef");
        var policy=dependency(bySource,used,policyRef.path("policyVersionId").asText(),"POLICY");
        check(policy.path("policyHash").equals(policyRef.path("policyHash")));
        var catalogs=artifacts.stream().filter(a->a.kind().equals("CATALOG")).toList();check(catalogs.size()==1);
        String catalogId=catalogs.getFirst().sourceId();var catalog=dependency(bySource,used,catalogId,"CATALOG");
        var personas=json.createArrayNode();
        for(String id:strings(s.path("personaRefs"))) {
            var persona=dependency(bySource,used,id,"PERSONA");
            personas.addObject().put("personaVersionId",id).put("contentHash",persona.path("contentHash").asText());
        }
        for(String actor:List.of("jun","nara")) {
            var a=catalog.path("actors").path(actor);
            var persona=dependency(bySource,used,a.path("personaRef").asText(),"PERSONA");
            check(persona.path("actorId").asText().equals(actor) && strings(s.path("personaRefs")).contains(a.path("personaRef").asText()));
            String model=cueBindings.get(actor+":MODEL:"+a.path("modelId").asText());
            check(model!=null && media(bySource,used,model).bytesHash().equals(a.path("modelHash").asText()));
            for(String kind:List.of("voice","lip","motion"))
                check(media(bySource,used,a.path(kind+"Ref").asText()).bytesHash().equals(a.path(kind+"Hash").asText()));
        }
        for(var recipe:catalog.path("recipes")) {
            var refs=new TreeSet<String>();for(String key:List.of("voiceRef","lipRef","motionRef")) refs.add(recipe.path(key).asText());
            for(var prop:recipe.path("propRefs")) {
                String id=prop.path("assetId").asText();refs.add(id);
                check(media(bySource,used,id).bytesHash().equals(prop.path("hash").asText()));
            }
            var env=json.createObjectNode();env.set("content",project(recipe,"contentHash","approvedAt"));
            env.putObject("bindings").set("assets",assets(bySource,used,refs));
            check(digest(env).equals(recipe.path("contentHash").asText()));
        }
        for(var scene:s.path("sceneGraph").path("scenes")) {
            var matches=new ArrayList<JsonNode>();
            for(var r:catalog.path("recipes")) if(scene.path("actorId").equals(r.path("actorId"))
                    && scene.path("animationCue").equals(r.path("cue")) && scene.path("voiceRef").equals(r.path("voiceRef"))) matches.add(r);
            check(matches.size()==1);var recipe=matches.getFirst();
            for(String kind:List.of("EXPRESSION","ANIMATION")) {
                String cue=scene.path(kind.equals("EXPRESSION")?"expressionCue":"animationCue").asText();
                String bound=cueBindings.get(scene.path("actorId").asText()+":"+kind+":"+cue);
                check(bound!=null && strings(s.path("assetRefs")).contains(bound));media(bySource,used,bound);
                if(kind.equals("ANIMATION")) check(bound.equals(recipe.path("motionRef").asText()));
                else { boolean found=false;for(var p:recipe.path("propRefs")) if(bound.equals(p.path("assetId").asText())) found=true;check(found); }
            }
        }
        var env=json.createObjectNode();env.set("content",project(s,"contentHash","status"));
        var bindings=env.putObject("bindings");bindings.set("personas",personas);bindings.set("policy",policyRef);
        bindings.set("assets",assets(bySource,used,strings(s.path("assetRefs"))));
        var cr=bindings.putObject("catalog");for(String k:List.of("catalogId","revision","contentHash")) cr.set(k,catalog.path(k));
        check(digest(env).equals(s.path("contentHash").asText()));
        // Unbound artifacts must not become accessible via a publication lease.
        check(used.equals(bySource.keySet()));
        var manifest=json.createObjectNode().put("schemaVersion","core-publication-1").put("payloadHash",LearningCatalog.hash(manuscript))
                .put("sourceContentHash",s.path("contentHash").asText()).put("validatorVersion",VERSION);
        var refs=manifest.putArray("artifacts");
        artifacts.stream().sorted(Comparator.comparing(Artifact::sourceId)).forEach(a->refs.addObject()
                .put("id",a.id().toString()).put("kind",a.kind()).put("sourceId",a.sourceId()).put("bytesHash",a.bytesHash()));
        manifest.set("cueBindings",json.valueToTree(new TreeMap<>(cueBindings)));
        String text=canonical(manifest).toString();
        var paths=new ArrayList<LearningCatalog.ReviewedPath>();var scenes=new HashMap<String,JsonNode>();
        for(var n:s.path("sceneGraph").path("scenes")) scenes.put(n.path("sceneId").asText(),n);
        walk(s,s.path("sceneGraph").path("startSceneId").asText(),scenes,List.of(),Map.of(),paths);
        return new Result(text,LearningCatalog.hash(text),List.copyOf(paths));
    }
    private void walk(JsonNode scenario,String id,Map<String,JsonNode> scenes,List<String> choices,Map<String,Object> effects,List<LearningCatalog.ReviewedPath> out) {
        var scene=scenes.get(id);check(scene!=null && out.size()<256);
        if(scene.path("end").asBoolean()) {
            var combined=new TreeMap<>(effects);effects(scenario.path("terminalEffects"),combined);
            for(var q:scenario.path("emotionQuestionByTerminal")) if(q.path("terminalSceneId").asText().equals(id)) {
                // All displayed choices, including UNKNOWN, are valid learning participation.
                var emotions=new ArrayList<String>();for(var o:q.path("options")) emotions.add(o.path("emotionId").asText());
                out.add(new LearningCatalog.ReviewedPath(choices,id,emotions,q.path("targetActorId").asText(),combined));return;
            }
            check(false);
        } else if(scene.has("nextSceneId")) walk(scenario,scene.path("nextSceneId").asText(),scenes,choices,effects,out);
        else for(var c:scene.path("choices")) {
            var next=new ArrayList<>(choices);next.add(c.path("choiceId").asText());var combined=new TreeMap<>(effects);effects(c.path("effects"),combined);
            walk(scenario,c.path("nextSceneId").asText(),scenes,next,combined,out);
        }
    }
    private static void effects(JsonNode nodes,Map<String,Object> out) {
        for(var e:nodes) {check(e.path("op").asText().equals("SET") && (e.path("value").isBoolean() || e.path("value").isTextual())
                && e.path("key").asText().matches("[a-z][a-z0-9_]{0,63}"));out.put(e.path("key").asText(),e.path("value").isBoolean()?e.path("value").asBoolean():e.path("value").asText());}
    }
    private JsonNode dependency(Map<String,Artifact> all,Set<String> used,String id,String kind) {
        Artifact a=all.get(id);check(a!=null && kind.equals(a.kind()));used.add(id);return parse(a.bytes());
    }
    private static Artifact media(Map<String,Artifact> all,Set<String> used,String id) {
        Artifact a=all.get(id);check(a!=null && a.kind().equals("MEDIA"));used.add(id);return a;
    }
    private JsonNode assets(Map<String,Artifact> all,Set<String> used,Collection<String> ids) {
        var result=json.createArrayNode();for(String id:new TreeSet<>(ids)) result.addObject().put("assetId",id).put("sha256",media(all,used,id).bytesHash());return result;
    }
    public static SortedSet<String> strings(JsonNode n) {var r=new TreeSet<String>();n.forEach(x->r.add(x.asText()));return r;}
    public static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}
    }
    public JsonNode canonical(JsonNode n) {
        if(n.isObject()) {var o=json.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String k:keys)o.set(k,canonical(n.get(k)));return o;}
        if(n.isArray()) {var a=json.createArrayNode();n.forEach(x->a.add(canonical(x)));return a;}return n;
    }
    public String digest(JsonNode n) {return sha(canonical(n).toString().getBytes(StandardCharsets.UTF_8));}
    public static ObjectNode project(JsonNode n,String...keys) {ObjectNode o=n.deepCopy();o.remove(List.of(keys));return o;}
    private static void check(boolean b) {if(!b)throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);}
}
