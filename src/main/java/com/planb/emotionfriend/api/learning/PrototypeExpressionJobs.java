package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.agent.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Nonblocking wrapper around the existing bounded expression generation and cost reservation. */
@Service
public class PrototypeExpressionJobs implements AutoCloseable {
    private final JdbcTemplate db;private final JsonMapper json;private final PrototypeExpressionService expressions;private final PrototypeContracts contracts;
    private final RequestFingerprint fingerprint;private final ReplayCipher cipher;private final Clock clock;private final TransactionTemplate tx;
    private final ExecutorService queue=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"expression-job");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public PrototypeExpressionJobs(JdbcTemplate db,JsonMapper json,PrototypeExpressionService expressions,PrototypeContracts contracts,RequestFingerprint fingerprint,ReplayCipher cipher,Clock clock,PlatformTransactionManager manager){
        this.db=db;this.json=json;this.expressions=expressions;this.contracts=contracts;this.fingerprint=fingerprint;this.cipher=cipher;this.clock=clock;tx=new TransactionTemplate(manager);
    }
    public Object create(ActorPrincipal actor,UUID session,int slot,JsonNode command,String key){
        contracts.validate("ExpressionJobCommandP1",command);if(key==null||key.length()<8||key.length()>128)throw new ApiException(ErrorCode.INVALID_SCHEMA);
        String kind=command.path("operation").asText();JsonNode request=command.path("request");
        UUID id=tx.execute(s->{expressions.validateJob(actor,session,slot,request,kind.equals("OPTIONS"));var scope=expressions.jobScope(actor,session,slot);
            String digest=fingerprint.request(command),kd=fingerprint.key(key);
            var old=db.queryForList("select id,request_digest from learning.prototype_expression_job where session_id=? and slot=? and kind=? and key_digest=?",session,slot,kind,kd);
            if(!old.isEmpty()){if(!digest.equals(old.getFirst().get("request_digest")))throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);return (UUID)old.getFirst().get("id");}
            db.update("update learning.prototype_expression_job set state='EXPIRED',encrypted_request=null where expires_at<=? and encrypted_request is not null",ts());
            var same=db.queryForList("select id from learning.prototype_expression_job where session_id=? and slot=? and kind=? and request_digest=? and state in ('QUEUED','RUNNING','COMPLETED','OUTCOME_UNKNOWN') and expires_at>? order by created_at limit 1",UUID.class,session,slot,kind,digest,ts());
            if(!same.isEmpty())return same.getFirst();
            UUID next=UUID.randomUUID();db.update("""
                insert into learning.prototype_expression_job(id,child_id,session_id,slot,attempt_id,draft_version,kind,context_hash,original_hash,key_digest,request_digest,state,encrypted_request,created_at,expires_at)
                values(?,?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?)
                """,next,UUID.fromString(actor.childId()),session,slot,UUID.fromString(scope.path("attemptId").asText()),request.path("draftVersion").asLong(),kind,request.path("contextHash").asText(),PrototypeJson.textHash(request.path("originalText").asText()),kd,digest,cipher.encrypt(json.writeValueAsBytes(request),"expression-job:"+next),ts(),Timestamp.from(clock.instant().plusSeconds(900)));
            return next;
        });
        try{queue.execute(()->run(actor,session,slot,id));}catch(RejectedExecutionException full){tx.executeWithoutResult(s->db.update("update learning.prototype_expression_job set state='FAILED',error_code='QUEUE_BUSY',encrypted_request=null where id=? and state='QUEUED'",id));}
        return status(actor,session,slot,id);
    }
    private Map<String,Object> require(ActorPrincipal actor,UUID session,int slot,UUID id){
        var rows=db.queryForList("select * from learning.prototype_expression_job where id=? and child_id=? and session_id=? and slot=?",id,UUID.fromString(actor.childId()),session,slot);
        if(rows.isEmpty())throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);return rows.getFirst();
    }
    public Object status(ActorPrincipal actor,UUID session,int slot,UUID id){return tx.execute(s->{
        var r=require(actor,session,slot,id);var scope=expressions.jobScope(actor,session,slot);String state=(String)r.get("state");
        if(!((Timestamp)r.get("expires_at")).toInstant().isAfter(clock.instant()))state="EXPIRED";
        if(!scope.path("attemptId").asText().equals(r.get("attempt_id").toString())||scope.path("draftVersion").asLong()!=((Number)r.get("draft_version")).longValue()||!scope.path("contextHash").asText().equals(r.get("context_hash")))state="DISCARDED";
        var out=json.createObjectNode().put("schemaVersion","EF_EXPRESSION_JOB_P1").put("jobId",id.toString()).put("childId",actor.childId()).put("sessionId",session.toString()).put("slot",slot)
            .put("operation",(String)r.get("kind")).put("state",state).put("draftVersion",((Number)r.get("draft_version")).longValue()).put("contextHash",(String)r.get("context_hash")).put("originalHash",(String)r.get("original_hash"));
        out.putNull("result");if(r.get("error_code")==null)out.putNull("errorCode");else out.put("errorCode",(String)r.get("error_code"));
        if(state.equals("COMPLETED"))out.set("result",expressions.jobResult(actor,session,slot,(UUID)r.get("result_ref"),(String)r.get("kind"),((Number)r.get("draft_version")).longValue(),(String)r.get("context_hash"),(String)r.get("original_hash")));
        return out;
    });}
    private void run(ActorPrincipal actor,UUID session,int slot,UUID id){
        var row=tx.execute(s->{var r=require(actor,session,slot,id);if(!r.get("state").equals("QUEUED"))return null;
            int claimed=db.update("update learning.prototype_expression_job set state='RUNNING' where id=? and state='QUEUED' and expires_at>?",id,ts());return claimed==1?r:null;});if(row==null)return;
        try{
            var request=json.readTree(cipher.decrypt((byte[])row.get("encrypted_request"),"expression-job:"+id));
            var result=expressions.generate(actor,session,slot,request,id.toString(),row.get("kind").equals("OPTIONS"));
            UUID ref=UUID.fromString(result.path(row.get("kind").equals("OPTIONS")?"offerId":"candidateId").asText());
            var expiry=Timestamp.from(Instant.parse(result.path("expiresAt").asText()));
            tx.executeWithoutResult(s->db.update("update learning.prototype_expression_job set state='COMPLETED',result_ref=?,expires_at=least(expires_at,?),encrypted_request=null where id=? and state='RUNNING'",ref,expiry,id));
        }catch(RuntimeException failure){tx.executeWithoutResult(s->{
            boolean unknown=Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from learning.prototype_expression_call where session_id=? and slot=? and key_digest=? and state in ('RESERVED','OUTCOME_UNKNOWN'))",Boolean.class,session,slot,fingerprint.key(id.toString())));
            db.update("update learning.prototype_expression_job set state=?,error_code=?,encrypted_request=null where id=? and state='RUNNING'",unknown?"OUTCOME_UNKNOWN":"FAILED",failure instanceof ApiException e?e.code().name():"EXPRESSION_UNAVAILABLE",id);
        });}
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void preserveOnRestart(){
        db.update("update learning.prototype_expression_job set state='OUTCOME_UNKNOWN',error_code='SERVER_RESTART',encrypted_request=null where state='RUNNING'");
        db.update("update learning.prototype_expression_job set state='FAILED',error_code='NOT_DISPATCHED',encrypted_request=null where state='QUEUED'");
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void discardExpiredRequests(){
        // 실행 중인 제공자 요청을 취소/재전송하지 않습니다. 보관 기한이 지난 원문만 지웁니다.
        db.update("update learning.prototype_expression_job set encrypted_request=null where expires_at<=? and encrypted_request is not null",ts());
    }
    private Timestamp ts(){return Timestamp.from(clock.instant());}
    public void close(){queue.shutdown();}
}
