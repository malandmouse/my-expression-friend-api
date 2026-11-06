package com.planb.emotionfriend.api.learning;

import static com.planb.emotionfriend.api.learning.RuntimeContracts.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class RuntimeLearningService {
    private final JdbcTemplate db;private final JsonMapper json;private final RuntimeCredentials auth;
    private final LearningLedger ledger;private final CorePublicationService content;private final IdempotencyService idempotency;
    private final ConfirmedExpressions expressions;private final Clock clock;private final long leaseSeconds;
    public RuntimeLearningService(JdbcTemplate db,JsonMapper json,RuntimeCredentials auth,LearningLedger ledger,CorePublicationService content,
            IdempotencyService idempotency,ConfirmedExpressions expressions,Clock clock,
            @Value("${emotion-friend.core.lease-seconds:0}") long leaseSeconds) {
        this.db=db;this.json=json;this.auth=auth;this.ledger=ledger;this.content=content;this.idempotency=idempotency;
        this.expressions=expressions;this.clock=clock;this.leaseSeconds=leaseSeconds;
        if(leaseSeconds<0 || leaseSeconds>30)throw new IllegalArgumentException("Lease TTL must be configured within Core's 30-second bound");
    }
    @Transactional public Object home(ActorPrincipal actor,UUID child) {requireChild(actor,child);return homeView(child);}
    public StoredResponse enter(ActorPrincipal actor,UUID child,Entry request,String key) {
        com.planb.emotionfriend.api.common.validation.RequestPreconditions.idempotencyKey(key);
        // eventId is the stable semantic key, including reconnect with a new runtime credential.
        return idempotency.execute(actor,"POST","/api/v1/children/"+child+"/mission-entries",request.eventId().toString(),request,
                ()->{requireChild(actor,child);device(actor,request.deviceId());},()->{ledger.enter(child);return response(200,homeView(child));});
    }
    public StoredResponse start(ActorPrincipal actor,UUID plan,Start request,String key) {
        return idempotency.execute(actor,"POST","/api/v1/daily-plans/"+plan+"/sessions",key,request,()->{
            auth.require(actor);device(actor,request.deviceId());
            if(actor.planId()!=null && !actor.planId().equals(plan.toString()))fail(ErrorCode.CHILD_ACCESS_DENIED);
            var rows=db.queryForList("select child_id from learning.plan where id=?",UUID.class,plan);
            if(rows.isEmpty() || !rows.getFirst().toString().equals(actor.childId()))fail(ErrorCode.RESOURCE_NOT_FOUND);
            for(int slot=1;slot<=5;slot++)content.execution(plan,slot);
            var old=db.queryForList("select id from learning.session where plan_id=?",UUID.class,plan);if(!old.isEmpty())checkpointLive(old.getFirst());
        },()->{
            var started=ledger.startPlan(UUID.fromString(actor.childId()),plan,new LearningContracts.SessionStart(request.schemaVersion(),request.deviceId()));
            UUID session=started.session().sessionId();
            if(db.queryForObject("select count(*) from learning.runtime_checkpoint where session_id=?",Integer.class,session)==0) {
                UUID attempt=UUID.randomUUID();var body=new LinkedHashMap<String,Object>();
                int activeSlot=Math.min(5,started.session().completedCount()+1);
                body.put("slot",activeSlot);body.put("attemptId",attempt);body.put("checkpointRevision",1);body.put("selectedChoiceIds",List.of());
                body.put("draftVersion",1);body.put("worldRevision",started.session().worldRevision());body.put("manifestHash",pin(session).get("plan_hash"));
                db.update("insert into learning.runtime_checkpoint values(?,?,?,1,?::jsonb,?)",session,activeSlot,attempt,json.writeValueAsString(body),ts());
            }
            return response(started.status(),new Started(started.session(),auth.issue(UUID.fromString(actor.actorId()),plan),pinView(session),checkpoint(session)));
        });
    }
    @Transactional public Object session(ActorPrincipal actor,UUID session) {
        sessionAccess(actor,session);return new Restored(ledger.session(session),pinView(session),checkpoint(session),"RECONFIRM_IF_PROOF_EXPIRED_OR_SERVER_RESTARTED");
    }
    public StoredResponse checkpoint(ActorPrincipal actor,UUID session,Checkpoint request,String key) {
        return idempotency.execute(actor,"PUT","/api/v1/sessions/"+session+"/checkpoint",key,request,
                ()->{sessionAccess(actor,session);checkpointLive(session);content.execution(ledger.session(session).planId(),request.slot());},
                ()->response(200,saveCheckpoint(actor,session,request,null)));
    }
    /** 한 번의 왕복에서 진행 저장과 해당 문장의 30초 재생 허가를 함께 확정합니다. */
    public StoredResponse checkpointPlayback(ActorPrincipal actor,UUID session,CheckpointPlayback request,String key){
        return idempotency.execute(actor,"PUT","/api/v1/sessions/"+session+"/checkpoint-and-playback",key,request,()->{
            sessionAccess(actor,session);
            if(!request.checkpoint().slot().equals(request.playback().slot())||request.checkpoint().presentationCursor()==null)fail(ErrorCode.INVALID_BRANCH_PATH);
            // 재전송 때에도 현재 동의/연결/발행/음원 철회를 확인합니다.
            asset(session,request.playback().slot(),request.playback().assetId(),request.playback().contentHash());
        },()->{
            var cp=saveCheckpoint(actor,session,request.checkpoint(),request.playback());
            return response(200,new Advanced(cp,issueLease(actor,session,request.playback())));
        });
    }
    private tools.jackson.databind.JsonNode saveCheckpoint(ActorPrincipal actor,UUID session,Checkpoint request,LeaseRequest playback){
        var current=checkpoint(session);var state=ledger.session(session);
        if(request.checkpointRevision()!=current.path("checkpointRevision").asLong() || !request.attemptId().toString().equals(current.path("attemptId").asText())
                || request.slot()!=state.completedCount()+1)fail(ErrorCode.STALE_CHECKPOINT);
        if(!request.manifestHash().equals(pin(session).get("plan_hash")))fail(ErrorCode.CONTENT_HASH_MISMATCH);
        if(request.worldRevision()!=state.worldRevision())fail(ErrorCode.STALE_WORLD_STATE);
        if(request.draftVersion()<current.path("draftVersion").asLong())fail(ErrorCode.STALE_DRAFT);
        validatePath(state,request);
        if(request.presentationCursor()!=null){var cursor=request.presentationCursor();
            if(!Objects.equals(cursor.sceneId(),request.sceneId()))fail(ErrorCode.INVALID_BRANCH_PATH);
            content.requirePresentationCursor(state.planId(),request.slot(),cursor.sceneId(),cursor.sentenceIndex(),playback);
        }
        if(request.confirmedExpressionRef()!=null)PrototypeExpressionProof.require(db,json,expressions,clock,request.confirmedExpressionRef(),session,request.slot(),actor.actorId(),request.draftVersion(),PrototypeExpressionProof.choice(db,request.confirmedExpressionRef()));
        var body=(tools.jackson.databind.node.ObjectNode)json.valueToTree(request);body.put("checkpointRevision",request.checkpointRevision()+1);
        db.update("update learning.runtime_checkpoint set revision=revision+1,body=?::jsonb,saved_at=? where session_id=?",body.toString(),ts(),session);
        return body;
    }
    public StoredResponse expression(ActorPrincipal actor,UUID session,int slot,LearningContracts.ExpressionRequest request,String key) {
        return idempotency.execute(actor,"POST","/api/v1/sessions/"+session+"/slots/"+slot+"/confirmed-expressions",key,request,
                ()->{sessionAccess(actor,session);checkpointLive(session);},()->response(201,ledger.expression(session,slot,actor.actorId(),request.draftVersion())));
    }
    public StoredResponse complete(ActorPrincipal actor,UUID session,int slot,Commit command,String key) {
        return idempotency.execute(actor,"POST","/api/v1/sessions/"+session+"/loops/"+slot+"/finalize",key,command,
                ()->{sessionAccess(actor,session);},()->{
            var state=ledger.session(session);var pin=pin(session);
            if(!command.manifestHash().equals(pin.get("plan_hash")))fail(ErrorCode.CONTENT_HASH_MISMATCH);
            if(state.slots().stream().noneMatch(s->s.slot()==slot && s.scenarioVersionId().equals(command.scenarioVersionId())))fail(ErrorCode.CONTENT_HASH_MISMATCH);
            boolean previous=db.queryForObject("select exists(select 1 from learning.receipt where session_id=? and slot=?)",Boolean.class,session,slot);
            if(!previous) {
                var cp=checkpoint(session);
                if(command.expectedCheckpointRevision()!=cp.path("checkpointRevision").asLong() || !command.attemptId().toString().equals(cp.path("attemptId").asText()))fail(ErrorCode.STALE_CHECKPOINT);
                var c=command.completion();
                if(!json.valueToTree(c.selectedChoiceIds()).equals(cp.path("selectedChoiceIds"))
                        || !c.terminalSceneId().equals(cp.path("terminalSceneId").asText()) || !c.selectedEmotionId().equals(cp.path("selectedEmotionId").asText())
                        || c.draftVersion()!=cp.path("draftVersion").asLong() || !c.confirmedExpressionRef().toString().equals(cp.path("confirmedExpressionRef").asText()))fail(ErrorCode.STALE_CHECKPOINT);
            }
            UUID guardian=db.queryForObject("select guardian_id from care.child where id=?",UUID.class,UUID.fromString(actor.childId()));
            return response(200,ledger.completeRuntime(session,slot,command,actor.actorId(),guardian));
        });
    }
    @Transactional public LearningContracts.Receipt receipt(ActorPrincipal actor,UUID session,int slot) {sessionAccess(actor,session);return ledger.receipt(session,slot);}
    @Transactional public Object content(ActorPrincipal actor,UUID session,int slot) {
        sessionAccess(actor,session);var state=ledger.session(session);content.execution(state.planId(),slot);
        if(PrototypeBinding.VERSION.equals(dependency(state.planId(),slot).get("validator_version")))fail(ErrorCode.UNSUPPORTED_SCHEMA_VERSION);
        UUID material=state.slots().stream().filter(s->s.slot()==slot).findFirst().orElseThrow(()->new ApiException(ErrorCode.INVALID_SCHEMA)).materialId();
        return Map.of("pin",pinView(session),"scenario",json.readTree((String)content.manuscript(material).get("payload")),"dependencyManifest",json.readTree((String)dependency(state.planId(),slot).get("manifest")));
    }
    @Transactional public Object runtimePackage(ActorPrincipal actor,UUID session,int slot) {
        sessionAccess(actor,session);var state=ledger.session(session);var source=content.prototypePackage(state.planId(),slot);var pin=pin(session);
        var out=json.createObjectNode().put("schemaVersion","EF_FOUNDATION_RUNTIME_P1");
        var scope=out.putObject("pin");scope.put("sessionId",session.toString()).put("planManifestHash",(String)pin.get("plan_hash")).put("entryWorldRevision",((Number)pin.get("entry_world_revision")).longValue());scope.set("planManifest",json.readTree((String)pin.get("manifest")));
        for(String field:List.of("scenario","dependencyManifest","presentation"))out.set(field,source.path(field));
        out.put("packageHash",com.planb.emotionfriend.api.agent.PrototypeJson.digest(out));return out;
    }
    public StoredResponse lease(ActorPrincipal actor,UUID session,LeaseRequest request,String key) {
        return idempotency.execute(actor,"POST","/api/v1/sessions/"+session+"/playback-leases",key,request,
                ()->{sessionAccess(actor,session);asset(session,request.slot(),request.assetId(),request.contentHash());},()->{
            return response(201,issueLease(actor,session,request));
        });
    }
    private Lease issueLease(ActorPrincipal actor,UUID session,LeaseRequest request){
        if(leaseSeconds==0)fail(ErrorCode.CONTENT_NOT_APPROVED);
        UUID id=UUID.randomUUID();Instant expires=clock.instant().plusSeconds(leaseSeconds);var d=auth.require(actor);
        db.update("insert into learning.asset_lease(id,device,generation,session_id,slot,plan_hash,asset_id,asset_hash,expires_at) values(?,?,?,?,?,?,?,?,?)",id,UUID.fromString(actor.actorId()),d.get("generation"),session,request.slot(),pin(session).get("plan_hash"),request.assetId(),request.contentHash(),Timestamp.from(expires));
        return new Lease(id,expires,request.contentHash());
    }
    public StoredResponse renewLease(ActorPrincipal actor,UUID lease,LeaseRequest request,String key) {
        return idempotency.execute(actor,"POST","/api/v1/playback-leases/"+lease+"/renew",key,request,()->{
            auth.require(actor);var old=leaseRow(lease);UUID session=(UUID)old.get("session_id");
            sessionAccess(actor,session);leaseBinding(actor,old,session,request.slot(),request.assetId());
            if(!old.get("asset_hash").equals(request.contentHash()))fail(ErrorCode.CONTENT_HASH_MISMATCH);
            asset(session,request.slot(),request.assetId(),request.contentHash());
        },()->{
            var old=leaseRow(lease);if(Boolean.TRUE.equals(old.get("superseded")))fail(ErrorCode.EXECUTION_LEASE_EXPIRED);
            if(leaseSeconds==0)fail(ErrorCode.CONTENT_NOT_APPROVED);UUID id=UUID.randomUUID();Instant expires=clock.instant().plusSeconds(leaseSeconds);
            db.update("insert into learning.asset_lease(id,device,generation,session_id,slot,plan_hash,asset_id,asset_hash,expires_at) values(?,?,?,?,?,?,?,?,?)",id,old.get("device"),old.get("generation"),old.get("session_id"),old.get("slot"),old.get("plan_hash"),old.get("asset_id"),old.get("asset_hash"),Timestamp.from(expires));
            db.update("update learning.asset_lease set superseded=true where id=?",lease);return response(201,new Lease(id,expires,request.contentHash()));
        });
    }
    /** Called twice by delivery controller, outside provider I/O. */
    @Transactional public String authorizeAsset(ActorPrincipal actor,UUID session,int slot,UUID asset,UUID lease) {
        sessionAccess(actor,session);var rows=db.queryForList("select * from learning.asset_lease where id=? and session_id=? and slot=? and asset_id=?",lease,session,slot,asset);
        if(rows.isEmpty())fail(ErrorCode.EXECUTION_LEASE_EXPIRED);var l=rows.getFirst();var d=auth.require(actor);
        if(Boolean.TRUE.equals(l.get("superseded")) || !l.get("device").toString().equals(actor.actorId()) || !l.get("generation").equals(d.get("generation"))
                || !l.get("plan_hash").equals(pin(session).get("plan_hash")) || !((Timestamp)l.get("expires_at")).toInstant().isAfter(clock.instant()))fail(ErrorCode.EXECUTION_LEASE_EXPIRED);
        asset(session,slot,asset,(String)l.get("asset_hash"));return (String)l.get("asset_hash");
    }
    private void asset(UUID session,int slot,UUID asset,String hash) {
        checkpointLive(session);var state=ledger.session(session);
        if(slot!=Math.min(5,state.completedCount()+1))fail(ErrorCode.INVALID_BRANCH_PATH);
        var verified=content.executionDependency(state.planId(),slot);var manifest=json.readTree((String)verified.get("manifest"));
        boolean found=false;for(var a:manifest.path("artifacts"))if(a.path("id").asText().equals(asset.toString()) && a.path("kind").asText().equals("MEDIA") && a.path("bytesHash").asText().equals(hash))found=true;
        if(!found)fail(ErrorCode.CONTENT_HASH_MISMATCH);content.requireMediaArtifact(asset,hash);
    }
    private Map<String,Object> leaseRow(UUID id) {var rows=db.queryForList("select * from learning.asset_lease where id=?",id);if(rows.isEmpty())fail(ErrorCode.EXECUTION_LEASE_EXPIRED);return rows.getFirst();}
    private void leaseBinding(ActorPrincipal actor,Map<String,Object> lease,UUID session,int slot,UUID asset) {
        var d=auth.require(actor);
        if(!lease.get("device").toString().equals(actor.actorId()) || !lease.get("generation").equals(d.get("generation"))
                || !lease.get("slot").equals(slot) || !lease.get("asset_id").equals(asset) || !lease.get("plan_hash").equals(pin(session).get("plan_hash"))
                || !((Timestamp)lease.get("expires_at")).toInstant().isAfter(clock.instant()))fail(ErrorCode.EXECUTION_LEASE_EXPIRED);
    }
    private Map<String,Object> dependency(UUID plan,int slot) {
        UUID publication=db.queryForObject("select publication_id from learning.plan_slot where plan_id=? and slot=?",UUID.class,plan,slot);
        UUID child=db.queryForObject("select child_id from learning.plan where id=?",UUID.class,plan);return content.publication(publication,child);
    }
    private void validatePath(LearningContracts.Session state,Checkpoint cp) {
        UUID material=state.slots().get(cp.slot()-1).materialId();var s=json.readTree((String)content.manuscript(material).get("payload"));
        var scenes=new HashMap<String,tools.jackson.databind.JsonNode>();s.path("sceneGraph").path("scenes").forEach(n->scenes.put(n.path("sceneId").asText(),n));
        String cursor=s.path("sceneGraph").path("startSceneId").asText();int choice=0;
        while(true) {
            var n=scenes.get(cursor);if(n==null)fail(ErrorCode.INVALID_BRANCH_PATH);
            if(cp.sceneId()!=null&&cp.sceneId().equals(cursor)&&choice==cp.selectedChoiceIds().size())break;
            if(n.path("end").asBoolean())break;
            if(n.has("nextSceneId")){cursor=n.path("nextSceneId").asText();continue;}
            if(choice==cp.selectedChoiceIds().size())break;
            String selected=cp.selectedChoiceIds().get(choice++);String next=null;
            for(var c:n.path("choices"))if(selected.equals(c.path("choiceId").asText()))next=c.path("nextSceneId").asText();
            if(next==null)fail(ErrorCode.INVALID_BRANCH_PATH);cursor=next;
        }
        if(choice!=cp.selectedChoiceIds().size() || cp.sceneId()!=null && !cp.sceneId().equals(cursor))fail(ErrorCode.INVALID_BRANCH_PATH);
        boolean end=scenes.get(cursor).path("end").asBoolean();
        if(cp.terminalSceneId()!=null && (!end || !cursor.equals(cp.terminalSceneId())))fail(ErrorCode.INVALID_BRANCH_PATH);
        if(cp.selectedEmotionId()!=null) {
            boolean found=false;for(var q:s.path("emotionQuestionByTerminal"))if(end && q.path("terminalSceneId").asText().equals(cursor))
                for(var option:q.path("options"))if(cp.selectedEmotionId().equals(option.path("emotionId").asText()))found=true;
            if(!found)fail(ErrorCode.INVALID_BRANCH_PATH);
        }
    }
    private void requireChild(ActorPrincipal actor,UUID child) {auth.require(actor);if(!actor.childId().equals(child.toString()))fail(ErrorCode.RESOURCE_NOT_FOUND);}
    long sessionAccess(ActorPrincipal actor,UUID session) {
        var device=auth.require(actor);var rows=db.queryForList("select p.id,p.child_id,s.device_id from learning.session s join learning.plan p on p.id=s.plan_id where s.id=?",session);
        if(rows.isEmpty())fail(ErrorCode.RESOURCE_NOT_FOUND);var s=rows.getFirst();
        if(!s.get("child_id").toString().equals(actor.childId()) || !s.get("device_id").equals(actor.deviceId())
                || actor.planId()==null || !actor.planId().equals(s.get("id").toString()))fail(ErrorCode.CHILD_ACCESS_DENIED);
        pin(session);
        return ((Number)device.get("generation")).longValue();
    }
    private Map<String,Object> pin(UUID session) {
        var rows=db.queryForList("select session_id,plan_hash,entry_world_revision,manifest from learning.session_pin where session_id=?",session);if(rows.isEmpty())fail(ErrorCode.CONTENT_NOT_APPROVED);
        var p=rows.getFirst();if(!LearningCatalog.hash((String)p.get("manifest")).equals(p.get("plan_hash")))fail(ErrorCode.CONTENT_HASH_MISMATCH);return p;
    }
    private Pin pinView(UUID session) {var p=pin(session);return new Pin(session,(String)p.get("plan_hash"),((Number)p.get("entry_world_revision")).longValue(),json.readTree((String)p.get("manifest")));}
    private tools.jackson.databind.JsonNode checkpoint(UUID session) {
        checkpointLive(session);return json.readTree(db.queryForObject("select body::text from learning.runtime_checkpoint where session_id=?",String.class,session));
    }
    void checkpointLive(UUID session) {
        var rows=db.queryForList("select c.saved_at,s.completed_at from learning.runtime_checkpoint c join learning.session s on s.id=c.session_id where c.session_id=?",session);
        if(rows.isEmpty())return;var r=rows.getFirst();Instant anchor=((Timestamp)(r.get("completed_at")==null?r.get("saved_at"):r.get("completed_at"))).toInstant();
        if(!clock.instant().isBefore(anchor.plus(Duration.ofDays(r.get("completed_at")==null?30:7))))fail(ErrorCode.CHECKPOINT_EXPIRED);
    }
    private Home homeView(UUID child) {return new Home(child,clock.instant(),db.query("select id,day_index,timezone,unlocked_date from learning.plan where child_id=? and revoked_at is null order by day_index limit 100",(rs,n)->new Mission(rs.getObject(1,UUID.class),rs.getInt(2),rs.getString(3),rs.getDate(4)==null?null:rs.getDate(4).toLocalDate()),child));}
    private void device(ActorPrincipal actor,String id) {if(!actor.deviceId().equals(id))fail(ErrorCode.CHILD_ACCESS_DENIED);}
    private StoredResponse response(int status,Object body) {return StoredResponse.json(json,status,body);}
    private Timestamp ts() {return Timestamp.from(clock.instant());}
    private static void fail(ErrorCode code) {throw new ApiException(code);}
}
