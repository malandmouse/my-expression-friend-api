package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.agent.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** receipt가 commit된 뒤에만 캐릭터 반응 음원을 한 번 예약한다. 실패해도 완료/별을 바꾸지 않는다. */
@Service
public class PrototypeReactionService implements AutoCloseable {
    private final JdbcTemplate db;private final RuntimeLearningService runtime;private final CorePublicationService content;private final AgentWorkerClient worker;private final Clock clock;private final JsonMapper json;private final TransactionTemplate tx;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"receipt-reaction");t.setDaemon(true);return t;});
    public PrototypeReactionService(JdbcTemplate db,RuntimeLearningService runtime,CorePublicationService content,AgentWorkerClient worker,Clock clock,JsonMapper json,PlatformTransactionManager manager){this.db=db;this.runtime=runtime;this.content=content;this.worker=worker;this.clock=clock;this.json=json;tx=new TransactionTemplate(manager);}
    static void record(JdbcTemplate db,LearningContracts.Session state,int slot,LearningContracts.FinalizeRequest request,LearningContracts.Receipt receipt,String npc) {
        var rows=db.queryForList("select reaction_text from learning.prototype_expression_proof where id=?",String.class,request.confirmedExpressionRef());if(rows.isEmpty())return;
        var mode=db.queryForObject("select r.mode from learning.prototype_day d join learning.prototype_release r on r.id=d.release_id where d.plan_id=?",String.class,state.planId());String text=rows.getFirst();
        db.update("insert into learning.prototype_reaction(receipt_id,child_id,actor_id,text,text_hash,mode,state) values(?,?,?,?,?,?,'PENDING')",receipt.receiptId(),state.childId(),npc,text,PrototypeJson.textHash(text),mode);
    }
    private Map<String,Object> require(ActorPrincipal actor,UUID session,int slot) {
        runtime.sessionAccess(actor,session);content.execution(db.queryForObject("select plan_id from learning.session where id=?",UUID.class,session),slot);var rows=db.queryForList("select p.* from learning.prototype_reaction p join learning.receipt r on r.id=p.receipt_id where r.session_id=? and r.slot=? and p.child_id=?",session,slot,UUID.fromString(actor.childId()));
        if(rows.isEmpty())throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from care.consent where child_id=? and scope='CORE_PROFILE_LEARNING' and granted and withdrawn_at is null)",Boolean.class,UUID.fromString(actor.childId()))))throw new ApiException(ErrorCode.CONSENT_REQUIRED);return rows.getFirst();
    }
    public Object status(ActorPrincipal actor,UUID session,int slot) {var r=tx.execute(s->require(actor,session,slot));UUID receipt=(UUID)r.get("receipt_id");
        if(r.get("state").equals("PENDING"))executor.execute(()->process(actor,session,slot));
        var out=json.createObjectNode().put("receiptId",receipt.toString()).put("actorId",(String)r.get("actor_id")).put("text",(String)r.get("text")).put("textHash",(String)r.get("text_hash")).put("state",(String)r.get("state"));
        if(r.get("asset_id")!=null)out.put("assetId",r.get("asset_id").toString()).put("audioHash",(String)r.get("audio_hash")).put("durationMs",((Number)r.get("duration_ms")).intValue());return out;
    }
    private void process(ActorPrincipal actor,UUID session,int slot) {
        Map<String,Object> row;
        try {row=tx.execute(s->{var r=require(actor,session,slot);if(!r.get("state").equals("PENDING"))return null;
            // The receipt locks the already approved voice configuration. No discovery/provider call before this point.
            var config=db.queryForObject("select pr.media_config::text from learning.receipt r join learning.prototype_day d on d.plan_id=r.plan_id join learning.prototype_release pr on pr.id=d.release_id where r.id=?",String.class,r.get("receipt_id"));
            if(config==null)throw new ApiException(ErrorCode.MATERIAL_NOT_READY);r.put("config",config);
            db.update("update learning.prototype_reaction set state='RESERVED',reserved_at=? where receipt_id=? and state='PENDING'",Timestamp.from(clock.instant()),r.get("receipt_id"));return r;});if(row==null)return;
        }catch(RuntimeException e){return;}
        UUID receipt=(UUID)row.get("receipt_id");
        try {
            var config=json.readTree((String)row.get("config"));var input=json.createObjectNode().put("configurationHash",config.path("configurationHash").asText()).put("text",(String)row.get("text")).put("actorId",(String)row.get("actor_id")).put("emotion","NEUTRAL").put("intensity",0.35);
            var audio=worker.sentenceMedia(input);byte[] bytes=Base64.getDecoder().decode(audio.path("audioBase64").asText());String hash=CoreDependencyValidator.sha(bytes);
            if(!hash.equals(audio.path("audioHash").asText())||!config.path("configurationHash").equals(audio.path("configurationHash"))||!row.get("mode").equals(audio.path("mode").asText()))throw new IllegalArgumentException("Reaction binding mismatch");PrototypeBinding.validateWave(bytes,audio.path("durationMs").asInt());
            tx.executeWithoutResult(s->{require(actor,session,slot);UUID id=UUID.randomUUID();content.artifact(new CoreDependencyValidator.Artifact(id,"MEDIA","receipt-reaction-"+receipt,bytes,hash));db.update("update learning.prototype_reaction set state='READY',asset_id=?,audio_hash=?,byte_length=?,duration_ms=?,completed_at=? where receipt_id=? and state='RESERVED'",id,hash,bytes.length,audio.path("durationMs").asInt(),Timestamp.from(clock.instant()),receipt);});
        }catch(RuntimeException e){String state=e instanceof AgentWorkerClient.WorkerRejected rejected&&!rejected.code().contains("UNKNOWN")?"FAILED":"OUTCOME_UNKNOWN";tx.executeWithoutResult(s->db.update("update learning.prototype_reaction set state=?,error_code='REACTION_UNAVAILABLE' where receipt_id=? and state='RESERVED'",state,receipt));}
    }
    @Transactional public byte[] audio(ActorPrincipal actor,UUID session,int slot,UUID asset,String expected){var r=require(actor,session,slot);if(!r.get("state").equals("READY")||!asset.equals(r.get("asset_id"))||!expected.equals(r.get("audio_hash")))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);byte[] bytes=content.readArtifact(asset).bytes();if(!CoreDependencyValidator.sha(bytes).equals(expected))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);return bytes;}
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void preserveUnknown(){db.update("update learning.prototype_reaction set state='OUTCOME_UNKNOWN',error_code='SERVER_RESTART' where state='RESERVED'");}
    public void close(){executor.shutdown();}
}
