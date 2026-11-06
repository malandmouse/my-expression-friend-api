package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.agent.*;
import com.planb.emotionfriend.api.common.error.*;
import java.util.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Named P1 validator: real sentence bytes + shipped app cue catalog; no fabricated model MEDIA/full review. */
@Component
public final class PrototypeBinding {
    public static final String VERSION="prototype-binding-v1";
    private final JsonMapper json;private final PrototypeContracts contracts;private final JsonNode cues,gptCues;
    public PrototypeBinding(JsonMapper json,PrototypeContracts contracts) {
        this.json=json;this.contracts=contracts;
        try(var in=getClass().getResourceAsStream("/contracts/installed-cues-p1.json")){cues=json.readTree(in);}catch(Exception e){throw new IllegalStateException(e);}
        try(var in=getClass().getResourceAsStream("/contracts/installed-cues-p2.json")){gptCues=json.readTree(in);}catch(Exception e){throw new IllegalStateException(e);}
    }
    public JsonNode cues(){return cues;}
    public JsonNode cuesForConfiguration(String configuration){return "prototype-st-v2.1-ko4".equals(configuration)?gptCues:cues;}
    public JsonNode installedCues(String id){
        if(cues.path("catalogId").asText().equals(id))return cues;
        if(gptCues.path("catalogId").asText().equals(id))return gptCues;
        throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
    }
    public String sourceHash(JsonNode source){var p=(tools.jackson.databind.node.ObjectNode)source.deepCopy();p.remove("contentHash");p.remove("status");return PrototypeJson.digest(p);}
    public List<LearningCatalog.ReviewedPath> validate(String payload,JsonNode manifest,JsonNode presentation,List<CoreDependencyValidator.Artifact> artifacts) {
        contracts.validate("BindingManifestP1",manifest);contracts.validate("PresentationP1",presentation);var source=json.readTree(payload);
        var projection=(tools.jackson.databind.node.ObjectNode)manifest.deepCopy();projection.remove("manifestHash");
        require(PrototypeJson.digest(projection).equals(manifest.path("manifestHash").asText()));
        require(sourceHash(source).equals(manifest.path("sourceContentHash").asText()));
        // 옛 발행의 v1 해시를 바꾸지 않고 v2 설치 목록도 별도로 검증합니다.
        var cues=installedCues(manifest.path("cueCatalogRef").asText());
        require(manifest.path("cueCatalogRef").asText().equals(cues.path("catalogId").asText())&&manifest.path("cueCatalogHash").asText().equals(PrototypeJson.digest(cues))
            &&manifest.path("installedAppAssetVersion").equals(cues.path("installedAppAssetVersion")));
        var assets=new HashMap<String,CoreDependencyValidator.Artifact>();artifacts.forEach(a->{require(a.kind().equals("MEDIA")&&CoreDependencyValidator.sha(a.bytes()).equals(a.bytesHash()));require(assets.put(a.id().toString(),a)==null);});
        var refs=new HashMap<String,JsonNode>();
        for(var ref:manifest.path("assets")) {
            String id=ref.path("assetId").asText();require(refs.put(id,ref)==null);var a=assets.get(id);
            require(a!=null&&a.sourceId().equals(ref.path("sourceId").asText())&&a.bytesHash().equals(ref.path("audioHash").asText())&&a.bytes().length==ref.path("byteLength").asInt());
            validateWave(a.bytes(),ref.path("durationMs").asInt());
        }
        require(refs.size()==assets.size());var used=new HashSet<String>();var scenes=new HashMap<String,JsonNode>();
        source.path("sceneGraph").path("scenes").forEach(n->scenes.put(n.path("sceneId").asText(),n));var seen=new HashSet<String>();
        for(var node:presentation.path("nodes")) {
            String id=node.path("sceneId").asText();require(seen.add(id)&&scenes.containsKey(id));var scene=scenes.get(id);var sentenceIds=new HashSet<String>();var text=new StringBuilder();
            for(var sentence:node.path("sentences")) {
                require(sentenceIds.add(sentence.path("sentenceId").asText()));String words=sentence.path("text").asText();text.append(words);
                require(PrototypeJson.textHash(words).equals(sentence.path("renderedTextHash").asText())&&sentence.path("actorId").equals(scene.path("actorId")));
                require(cues.path("faceCues").valueStream().anyMatch(n->n.equals(sentence.path("faceCue")))&&cues.path("animationCues").valueStream().anyMatch(n->n.equals(sentence.path("animationCue"))));
                String asset=sentence.path("voiceAssetId").asText();var ref=refs.get(asset);require(ref!=null&&ref.path("audioHash").equals(sentence.path("audioHash")));used.add(asset);
            }
            require(text.toString().equals(scene.path("dialogue").asText()));
        }
        require(seen.equals(scenes.keySet())&&used.equals(refs.keySet()));
        var paths=new ArrayList<LearningCatalog.ReviewedPath>();walk(source,source.path("sceneGraph").path("startSceneId").asText(),scenes,List.of(),paths);return paths;
    }
    private void walk(JsonNode source,String id,Map<String,JsonNode> scenes,List<String> choices,List<LearningCatalog.ReviewedPath> out) {
        var scene=scenes.get(id);require(scene!=null&&out.size()<256);
        if(scene.path("end").asBoolean()) {
            var q=source.path("emotionQuestionByTerminal").valueStream().filter(n->n.path("terminalSceneId").asText().equals(id)).findFirst().orElseThrow(()->new ApiException(ErrorCode.INVALID_BRANCH_PATH));
            out.add(new LearningCatalog.ReviewedPath(choices,id,q.path("options").valueStream().map(n->n.path("emotionId").asText()).toList(),scene.path("actorId").asText(),Map.of()));
        } else if(scene.has("nextSceneId"))walk(source,scene.path("nextSceneId").asText(),scenes,choices,out);
        else for(var c:scene.path("choices")){var selected=new ArrayList<>(choices);selected.add(c.path("choiceId").asText());walk(source,c.path("nextSceneId").asText(),scenes,selected,out);}
    }
    public static void validateWave(byte[] bytes,int duration) {
        require(bytes.length>=44&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WAVE"));
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(b.getInt(4)==bytes.length-8&&new String(bytes,12,4,StandardCharsets.US_ASCII).equals("fmt ")&&b.getInt(16)==16&&b.getShort(20)==1&&b.getShort(22)==1&&b.getInt(24)==24000&&b.getShort(34)==16
            &&new String(bytes,36,4,StandardCharsets.US_ASCII).equals("data")&&b.getInt(40)==bytes.length-44&&(bytes.length-44)%2==0);
        int actual=(int)Math.round((bytes.length-44)*1000.0/48000);require(actual==duration&&duration>=100&&duration<=30000);
    }
    private static void require(boolean value){if(!value)throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);}
}
