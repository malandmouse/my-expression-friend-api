package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** One already-published NPC sentence for output checking, even after five completions.
 * Read-only: no session start, provider call, observation, receipt or reward. */
@RestController
@RequestMapping("/api/v1/child-sessions/voice-check")
public class PrototypeVoiceCheckController {
    private final CurrentActor actors;private final RuntimeCredentials auth;private final JdbcTemplate db;private final CorePublicationService content;
    public PrototypeVoiceCheckController(CurrentActor actors,RuntimeCredentials auth,JdbcTemplate db,CorePublicationService content){this.actors=actors;this.auth=auth;this.db=db;this.content=content;}
    private Map<String,Object> sample(UUID asset,int index){
        var actor=actors.require(ActorPrincipal.Kind.CHILD);auth.require(actor);
        String select="""
            select m.*,d.plan_id,s.input_json->>'configurationRef' as presentation_configuration
              from learning.prototype_day d join learning.prototype_release r on r.id=d.release_id
              join learning.generation_job j on j.id=r.job_id join learning.agent_source s on s.id=j.source_ref
              join learning.prototype_media m on m.release_id=r.id
            where d.child_id=? and r.state='READY' and m.state='COMPLETED'
            """;
        var rows=asset==null?db.queryForList(select+" order by d.program_day desc,m.slot,m.scene_id,m.sentence_index limit 1 offset ?",UUID.fromString(actor.childId()),index):
            db.queryForList(select+" and m.artifact_id=? limit 1",UUID.fromString(actor.childId()),asset);
        if(rows.isEmpty())throw new ApiException(ErrorCode.MATERIAL_NOT_READY);var row=rows.getFirst();
        content.execution((UUID)row.get("plan_id"),((Number)row.get("slot")).intValue());
        return row;
    }
    @GetMapping @Transactional public ResponseEntity<Object> metadata(@RequestParam(defaultValue="0") int index){
        if(index<0||index>100)throw new ApiException(ErrorCode.INVALID_SCHEMA);var r=sample(null,index);
        var view=new LinkedHashMap<String,Object>(Map.of("schemaVersion","EF_VOICE_CHECK_P1","childId",actors.require(ActorPrincipal.Kind.CHILD).childId(),
            "assetId",r.get("artifact_id"),"audioHash",r.get("audio_hash"),"text",r.get("text"),"textHash",r.get("text_hash"),"actorId",r.get("actor_id"),"durationMs",r.get("duration_ms"),"mode",r.get("mode"),"readOnly",true));
        view.put("semanticEmotion",r.get("emotion"));view.put("emotionIntensity",r.get("intensity"));view.put("animationCue",r.get("animation_cue"));
        view.put("gptPresentation","prototype-st-v2.1-ko4".equals(r.get("presentation_configuration")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
    @GetMapping("/{asset}/audio") @Transactional public ResponseEntity<byte[]> audio(@PathVariable UUID asset,@RequestHeader("X-Expected-SHA256") String expected){
        var r=sample(asset,0);if(!expected.equals(r.get("audio_hash")))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
        byte[] bytes=content.readArtifact(asset).bytes();if(!CoreDependencyValidator.sha(bytes).equals(expected))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Content-SHA256",expected).body(bytes);
    }
}
