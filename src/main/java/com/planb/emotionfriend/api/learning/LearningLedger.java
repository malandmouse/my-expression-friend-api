package com.planb.emotionfriend.api.learning;

import static com.planb.emotionfriend.api.learning.LearningContracts.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.RequestFingerprint;
import com.planb.emotionfriend.api.membership.*;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Sole regular-execution writer: completion, reward, fictional effects and immutable receipt.
 * Trusted adapters MUST check current access on every call/replay in the surrounding transaction.
 * No authentication tokens or HTTP/idempotency response storage belong to this ledger.
 * Adult and runtime adapters share this transaction boundary and receipt snapshots.
 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class LearningLedger {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final MemberGuard guard;
    private final AuditTrail audit;
    private final RequestFingerprint fingerprint;
    private final ConfirmedExpressions expressions;
    private final Clock clock;
    private final Validator validator;
    private final CorePublicationService publications;
    public LearningLedger(JdbcTemplate jdbc,JsonMapper mapper,MemberGuard guard,AuditTrail audit,
            RequestFingerprint fingerprint,ConfirmedExpressions expressions,Clock clock,Validator validator,CorePublicationService publications) {
        this.jdbc=jdbc;this.mapper=mapper;this.guard=guard;this.audit=audit;this.fingerprint=fingerprint;
        this.expressions=expressions;this.clock=clock;this.validator=validator;
        this.publications=publications;
    }
    public record Started(int status,Session session) {}
    public Started start(UUID child,SessionStart request) {
        guard.lockDomain();validate(request);
        var plans=jdbc.queryForList("select id,timezone from learning.plan where child_id=? and day_index=1 and revoked_at is null",child);
        if(plans.isEmpty()) throw new ApiException(ErrorCode.MATERIAL_NOT_READY);
        UUID plan=(UUID)plans.getFirst().get("id");
        return startPlan(child,plan,request);
    }
    public Started startPlan(UUID child,UUID plan,SessionStart request) {
        guard.lockDomain();validate(request);
        var plans=jdbc.queryForList("select * from learning.plan where id=? and child_id=? and revoked_at is null",plan,child);
        if(plans.isEmpty()) throw new ApiException(ErrorCode.MATERIAL_NOT_READY);
        var prepared=plans.getFirst();
        var previous=jdbc.queryForList("select id,device_id from learning.session where plan_id=?",plan);
        if(!previous.isEmpty()) {
            if(!previous.getFirst().get("device_id").equals(request.deviceId())) throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
            return new Started(200,sessionView((UUID)previous.getFirst().get("id")));
        }
        for(int slot=1;slot<=5;slot++) requireMaterial(plan,slot);
        ZoneId zone=ZoneId.of((String)plans.getFirst().get("timezone"));
        Instant now=clock.instant();
        LocalDate date=now.atZone(zone).toLocalDate();
        if(prepared.get("unlocked_date")==null) {
            if(((Number)prepared.get("day_index")).intValue()!=1) throw new ApiException(ErrorCode.MATERIAL_NOT_READY);
            jdbc.update("update learning.plan set unlocked_date=?,unlocked_at=? where id=?",java.sql.Date.valueOf(date),ts(now),plan);
        } else if(!date.equals(((java.sql.Date)prepared.get("unlocked_date")).toLocalDate())) throw new ApiException(ErrorCode.REWARD_WINDOW_EXPIRED);
        jdbc.update("insert into learning.child_world values(?,0) on conflict do nothing",child);
        long world=jdbc.queryForObject("select revision from learning.child_world where child_id=?",Long.class,child);
        if(prepared.get("manifest")!=null && mapper.readTree((String)prepared.get("manifest")).path("entryWorldRevision").asLong()!=world)
            throw new ApiException(ErrorCode.STALE_WORLD_STATE);
        UUID session=UUID.randomUUID();
        jdbc.update("""
                insert into learning.session(id,plan_id,device_id,local_date,reward_expires_at,started_at,world_revision)
                values(?,?,?,?,?,?,?)
                """,session,plan,request.deviceId(),java.sql.Date.valueOf(date),ts(date.plusDays(1).atStartOfDay(zone).toInstant()),ts(now),world);
        if(prepared.get("manifest")!=null) jdbc.update("insert into learning.session_pin values(?,?,?,?,?)",session,prepared.get("manifest_hash"),world,prepared.get("manifest"),ts(now));
        return new Started(201,sessionView(session));
    }
    /** Explicit HOME/MISSION_MENU action only. Reads and credential refresh never call this. */
    public void enter(UUID child) {
        guard.lockDomain();
        var current=jdbc.queryForList("select * from learning.plan where child_id=? and unlocked_date is not null order by day_index desc limit 1",child);
        if(current.isEmpty())return;
        var p=current.getFirst();var today=clock.instant().atZone(ZoneId.of((String)p.get("timezone"))).toLocalDate();
        if(!today.isAfter(((java.sql.Date)p.get("unlocked_date")).toLocalDate()))return;
        var next=jdbc.queryForList("select id from learning.plan where child_id=? and day_index=? and revoked_at is null",UUID.class,child,((Number)p.get("day_index")).intValue()+1);
        if(next.isEmpty())return;
        for(int slot=1;slot<=5;slot++)requireMaterial(next.getFirst(),slot);
        jdbc.update("update learning.plan set unlocked_date=?,unlocked_at=? where id=? and unlocked_date is null",java.sql.Date.valueOf(today),ts(clock.instant()),next.getFirst());
    }
    public Session session(UUID session) {
        guard.lockDomain();return sessionView(session);
    }
    public Expression expression(UUID session,int slot,String actorId,long draftVersion) {
        guard.lockDomain();checkSlot(slot);
        if(draftVersion<1) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        var state=sessionView(session);
        requireWritable(state,slot);requireMaterial(state.planId(),slot);requireWindow(session);
        return expressions.confirm(session,slot,actorId,draftVersion);
    }
    public Receipt complete(UUID session,int slot,FinalizeRequest request,String actorId) {
        return complete(session,slot,request,actorId,UUID.fromString(actorId));
    }
    public Receipt complete(UUID session,int slot,FinalizeRequest request,String actorId,UUID auditActor) {
        guard.lockDomain();checkSlot(slot);validate(request);
        String digest=fingerprint.request(request);
        var previous=jdbc.queryForList("select request_digest,response::text from learning.receipt where session_id=? and slot=?",session,slot);
        if(!previous.isEmpty()) {
            if(!previous.getFirst().get("request_digest").equals(digest)) throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return mapper.readValue((String)previous.getFirst().get("response"),Receipt.class);
        }
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from learning.receipt where session_id=? and client_commit_id=?)",Boolean.class,session,request.clientCommitId())))
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
        var state=sessionView(session);
        requireWindow(session);
        requireWritable(state,slot);
        if(state.revision()!=request.expectedSessionRevision()) throw new ApiException(ErrorCode.STALE_CHECKPOINT);
        if(state.worldRevision()!=request.expectedWorldRevision()) throw new ApiException(ErrorCode.STALE_WORLD_STATE);
        var material=requireMaterial(state.planId(),slot);
        expressions.require(request.confirmedExpressionRef(),session,slot,actorId,request.draftVersion());
        var path=material.paths().stream().filter(p->p.choiceIds().equals(request.selectedChoiceIds())
                && p.terminalSceneId().equals(request.terminalSceneId()) && p.emotionIds().contains(request.selectedEmotionId()))
                .findFirst().orElseThrow(()->new ApiException(ErrorCode.INVALID_BRANCH_PATH));
        UUID receipt=UUID.randomUUID();
        Instant now=clock.instant();
        var result=new Receipt(receipt,session,state.planId(),slot,now,state.worldRevision()+1,1,
                state.stars()+1,state.completedCount()+1,request.confirmedExpressionRef(),request.draftVersion(),
                "ORIGINAL",path.actorId(),"CHARACTER_REPLY");
        jdbc.update("""
                insert into learning.receipt(id,session_id,plan_id,slot,client_commit_id,request_digest,response,completed_at)
                values(?,?,?,?,?,?,?::jsonb,?)
                """,receipt,session,state.planId(),slot,request.clientCommitId(),digest,mapper.writeValueAsString(result),ts(now));
        jdbc.update("insert into learning.star_award(receipt_id,session_id,slot,amount) values(?,?,?,1)",receipt,session,slot);
        jdbc.update("insert into learning.story_event(receipt_id,child_id,world_revision,effects,recorded_at) values(?,?,?,?::jsonb,?)",
                receipt,state.childId(),result.worldRevision(),mapper.writeValueAsString(path.effects()),ts(now));
        jdbc.update("""
                update learning.session set revision=revision+1,world_revision=?,
                    stars=stars+1,completed_count=completed_count+1,completed_at=? where id=?
                """,result.worldRevision(),result.completedCount()==5?ts(now):null,session);
        jdbc.update("update learning.child_world set revision=? where child_id=?",result.worldRevision(),state.childId());
        var checkpoint=jdbc.queryForList("select revision from learning.runtime_checkpoint where session_id=?",Long.class,session);
        if(!checkpoint.isEmpty()) {
            long revision=checkpoint.getFirst()+1;UUID attempt=UUID.randomUUID();
            var body=new LinkedHashMap<String,Object>();body.put("slot",Math.min(5,slot+1));body.put("attemptId",attempt);body.put("checkpointRevision",revision);
            body.put("selectedChoiceIds",List.of());body.put("draftVersion",1);body.put("worldRevision",result.worldRevision());
            body.put("completed",slot==5);body.put("lastReceiptId",receipt);
            body.put("manifestHash",jdbc.queryForObject("select plan_hash from learning.session_pin where session_id=?",String.class,session));
            jdbc.update("update learning.runtime_checkpoint set slot=?,attempt_id=?,revision=?,body=?::jsonb,saved_at=? where session_id=?",Math.min(5,slot+1),attempt,revision,mapper.writeValueAsString(body),ts(now),session);
        }
        audit.record(auditActor,"LEARNING_SLOT_COMPLETED",receipt,null,
                Map.of("completedCount",state.completedCount()),Map.of("sessionId",session,"slot",slot,"starsAwarded",1));
        return result;
    }
    public Receipt completeRuntime(UUID session,int slot,RuntimeContracts.Commit command,String actorId,UUID auditActor) {
        guard.lockDomain();String digest=fingerprint.request(command);
        var previous=jdbc.queryForList("select runtime_request_digest from learning.receipt where session_id=? and slot=?",session,slot);
        if(!previous.isEmpty() && !digest.equals(previous.getFirst().get("runtime_request_digest")))throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
        var result=complete(session,slot,command.completion(),actorId,auditActor);
        if(previous.isEmpty())jdbc.update("update learning.receipt set runtime_request_digest=? where id=?",digest,result.receiptId());
        return result;
    }
    public Receipt receipt(UUID session,int slot) {
        guard.lockDomain();checkSlot(slot);
        var rows=jdbc.queryForList("select response::text from learning.receipt where session_id=? and slot=?",String.class,session,slot);
        if(rows.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return mapper.readValue(rows.getFirst(),Receipt.class);
    }
    private Session sessionView(UUID session) {
        var slots=jdbc.query("""
                select ps.slot,m.id,m.scenario_version_id,r.id as receipt_id from learning.session s
                join learning.plan_slot ps on ps.plan_id=s.plan_id join learning.material m on m.id=ps.material_id
                left join learning.receipt r on r.session_id=s.id and r.slot=ps.slot where s.id=? order by ps.slot
                """,(rs,n)->new SessionSlot(rs.getInt(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                        rs.getObject(4)!=null,rs.getObject(4,UUID.class)),session);
        return jdbc.queryForObject("""
                select s.*,p.child_id,p.day_index,p.timezone,coalesce(w.revision,s.world_revision) as child_world_revision
                from learning.session s join learning.plan p on p.id=s.plan_id left join learning.child_world w on w.child_id=p.child_id where s.id=?
                """,(rs,n)->new Session(session,rs.getObject("child_id",UUID.class),rs.getObject("plan_id",UUID.class),
                        rs.getInt("day_index"),"REGULAR",rs.getDate("local_date").toLocalDate(),rs.getString("timezone"),
                        rs.getTimestamp("completed_at")==null?SessionStatus.ACTIVE:SessionStatus.COMPLETED,
                        rs.getLong("revision"),rs.getLong("child_world_revision"),rs.getInt("stars"),rs.getInt("completed_count"),slots),session);
    }
    private LearningCatalog.ReviewedMaterial requireMaterial(UUID plan,int slot) {
        var core=jdbc.queryForList("select m.id,m.scenario_version_id,m.subject,m.summary from learning.plan_slot ps join learning.material m on m.id=ps.material_id where ps.plan_id=? and ps.slot=? and ps.publication_id is not null",plan,slot);
        if(!core.isEmpty()) {var m=core.getFirst();return new LearningCatalog.ReviewedMaterial((UUID)m.get("id"),(UUID)m.get("scenario_version_id"),(String)m.get("subject"),(String)m.get("summary"),"CORE_EVIDENCE",publications.execution(plan,slot));}
        var rows=jdbc.queryForList("""
                select m.content::text,m.content_hash,ps.content_hash as pinned_hash from learning.plan p
                join learning.plan_slot ps on ps.plan_id=p.id join learning.material m on m.id=ps.material_id
                where p.id=? and ps.slot=? and p.revoked_at is null and m.current and m.revoked_at is null and m.status='CONFIRMED' and m.content_origin='LEGACY_REVIEWED'
                """,plan,slot);
        if(rows.isEmpty()) throw new ApiException(ErrorCode.MATERIAL_NOT_READY);
        var row=rows.getFirst();
        var material=mapper.readValue((String)row.get("content"),LearningCatalog.ReviewedMaterial.class);
        if(!row.get("content_hash").equals(row.get("pinned_hash"))
                || !row.get("content_hash").equals(LearningCatalog.hash(mapper.writeValueAsString(material))))
            throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
        return material;
    }
    private void requireWindow(UUID session) {
        Instant end=jdbc.queryForObject("select reward_expires_at from learning.session where id=?",Timestamp.class,session).toInstant();
        if(!clock.instant().isBefore(end)) throw new ApiException(ErrorCode.REWARD_WINDOW_EXPIRED);
    }
    private void requireWritable(Session state,int slot) {
        if(state.completedCount()+1!=slot || state.status()!=SessionStatus.ACTIVE) throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    private void validate(Object request) {
        if(request==null || !validator.validate(request).isEmpty()) throw new ApiException(ErrorCode.INVALID_SCHEMA);
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static void checkSlot(int slot) { if(slot<1 || slot>5) throw new ApiException(ErrorCode.INVALID_SCHEMA); }
}
