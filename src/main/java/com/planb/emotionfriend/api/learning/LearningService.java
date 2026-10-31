package com.planb.emotionfriend.api.learning;

import static com.planb.emotionfriend.api.learning.LearningContracts.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.membership.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class LearningService {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final LearningAccess access;
    private final MemberGuard guard;
    private final AuditTrail audit;
    private final IdempotencyService idempotency;
    private final RequestFingerprint fingerprint;
    private final ConfirmedExpressions expressions;
    private final Clock clock;
    public LearningService(JdbcTemplate jdbc,JsonMapper mapper,LearningAccess access,MemberGuard guard,
            AuditTrail audit,IdempotencyService idempotency,RequestFingerprint fingerprint,
            ConfirmedExpressions expressions,Clock clock) {
        this.jdbc=jdbc;this.mapper=mapper;this.access=access;this.guard=guard;this.audit=audit;
        this.idempotency=idempotency;this.fingerprint=fingerprint;this.expressions=expressions;this.clock=clock;
    }

    @Transactional
    public Page<SummaryItem> summaries(ActorPrincipal actor,UUID child,MaterialStatus status,UUID cursor,int limit) {
        access.view(actor,child);
        return summaryPage(child,status,cursor,limit);
    }
    private Page<SummaryItem> summaryPage(UUID child,MaterialStatus status,UUID cursor,int limit) {
        pageLimit(limit);
        var items=jdbc.query("""
                select summary_id,id,subject,summary,status from learning.material
                where child_id=? and current and revoked_at is null
                and (?::text is null and status<>'CONFIRMED' or status=?::text)
                and (?::uuid is null or id>?::uuid) order by id limit ?
                """,(rs,n)->new SummaryItem(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getString(3),rs.getString(4),MaterialStatus.valueOf(rs.getString(5))),
                child,status==null?null:status.name(),status==null?null:status.name(),cursor,cursor,limit+1);
        boolean more=items.size()>limit;
        var visible=List.copyOf(items.subList(0,Math.min(limit,items.size())));
        return new Page<>(child,visible,more?visible.getLast().materialId():null);
    }
    public StoredResponse confirm(ActorPrincipal actor,UUID child,ConfirmationRequest request,String key) {
        String path="/api/v1/children/"+child+"/learning-material-confirmations";
        return idempotency.execute(actor,"POST",path,key,request,()->{
            access.view(actor,child);
            // A retired summary cannot be recovered through an older successful key either.
            for(UUID id:request.summaryIds()) {
                var live=jdbc.queryForList("select current,revoked_at from learning.material where summary_id=? and child_id=?",id,child);
                if(live.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
                if(!Boolean.TRUE.equals(live.getFirst().get("current")) || live.getFirst().get("revoked_at")!=null)
                    throw new ApiException(ErrorCode.STALE_DRAFT);
            }
        },()->{
            List<Map<String,Object>> rows=new ArrayList<>();
            // Validate the whole selection before making any changes.
            for(UUID id:request.summaryIds()) {
                var found=jdbc.queryForList("select id,status,current,revoked_at from learning.material where summary_id=? and child_id=? for update",id,child);
                if(found.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
                var row=found.getFirst();
                if(!Boolean.TRUE.equals(row.get("current")) || row.get("revoked_at")!=null) throw new ApiException(ErrorCode.STALE_DRAFT);
                rows.add(row);
            }
            String next=request.decision()==Decision.CONFIRM?"CONFIRMED":"ON_HOLD";
            List<SummaryItem> changed=new ArrayList<>();
            for(var row:rows) {
                UUID id=(UUID)row.get("id");
                jdbc.update("update learning.material set status=?,reviewed_at=?,reviewer_id=? where id=?",
                        next,ts(clock.instant()),UUID.fromString(actor.actorId()),id);
                audit.record(UUID.fromString(actor.actorId()),"MATERIAL_"+request.decision(),id,null,
                        Map.of("status",row.get("status")),Map.of("status",next));
                changed.add(jdbc.queryForObject("select summary_id,id,subject,summary,status from learning.material where id=?",
                        (rs,n)->new SummaryItem(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),MaterialStatus.valueOf(rs.getString(5))),id));
            }
            return json(200,new Page<>(child,changed,null));
        });
    }

    public StoredResponse start(ActorPrincipal actor,UUID child,SessionStart request,String key) {
        return idempotency.execute(actor,"POST","/api/v1/children/"+child+"/learning-sessions",key,request,
                ()->access.manage(actor,child),()->{
            var plans=jdbc.queryForList("select id,timezone from learning.plan where child_id=? and day_index=1 and revoked_at is null",child);
            if(plans.isEmpty()) throw new ApiException(ErrorCode.MATERIAL_NOT_READY);
            UUID plan=(UUID)plans.getFirst().get("id");
            var previous=jdbc.queryForList("select id,device_id from learning.session where plan_id=?",plan);
            if(!previous.isEmpty()) {
                if(!previous.getFirst().get("device_id").equals(request.deviceId())) throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
                return json(200,sessionView((UUID)previous.getFirst().get("id")));
            }
            for(int slot=1;slot<=5;slot++) requireMaterial(plan,slot);
            ZoneId zone=ZoneId.of((String)plans.getFirst().get("timezone"));
            Instant now=clock.instant();
            LocalDate date=now.atZone(zone).toLocalDate();
            UUID session=UUID.randomUUID();
            jdbc.update("""
                    insert into learning.session(id,plan_id,device_id,local_date,reward_expires_at,started_at)
                    values(?,?,?,?,?,?)
                    """,session,plan,request.deviceId(),java.sql.Date.valueOf(date),ts(date.plusDays(1).atStartOfDay(zone).toInstant()),ts(now));
            return json(201,sessionView(session));
        });
    }

    @Transactional
    public Session session(ActorPrincipal actor,UUID session) {
        sessionAccess(actor,session,false);
        return sessionView(session);
    }
    public StoredResponse expression(ActorPrincipal actor,UUID session,int slot,ExpressionRequest request,String key) {
        checkSlot(slot);
        return idempotency.execute(actor,"POST","/api/v1/learning-sessions/"+session+"/slots/"+slot+"/confirmed-expressions",key,request,
                ()->sessionAccess(actor,session,true),()->{
            var state=sessionView(session);
            requireWritable(state,slot);
            requireMaterial(state.planId(),slot);
            requireWindow(session);
            // The fingerprint is an HMAC; neither it nor replay storage retains raw text.
            return json(201,expressions.confirm(session,slot,actor.actorId(),request.draftVersion()));
        });
    }

    public StoredResponse complete(ActorPrincipal actor,UUID session,int slot,FinalizeRequest request,String key) {
        checkSlot(slot);
        return idempotency.execute(actor,"POST","/api/v1/learning-sessions/"+session+"/slots/"+slot+"/finalize",key,request,
                ()->sessionAccess(actor,session,true),()->{
            String digest=fingerprint.request(request);
            var previous=jdbc.queryForList("select request_digest,response::text from learning.receipt where session_id=? and slot=?",session,slot);
            if(!previous.isEmpty()) {
                if(!previous.getFirst().get("request_digest").equals(digest)) throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
                return json(200,mapper.readValue((String)previous.getFirst().get("response"),Receipt.class));
            }
            if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from learning.receipt where session_id=? and client_commit_id=?)",Boolean.class,session,request.clientCommitId())))
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
            var state=sessionView(session);
            requireWindow(session);
            requireWritable(state,slot);
            if(state.revision()!=request.expectedSessionRevision()) throw new ApiException(ErrorCode.STALE_CHECKPOINT);
            if(state.worldRevision()!=request.expectedWorldRevision()) throw new ApiException(ErrorCode.STALE_WORLD_STATE);
            var material=requireMaterial(state.planId(),slot);
            expressions.require(request.confirmedExpressionRef(),session,slot,actor.actorId(),request.draftVersion());
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
                    update learning.session set revision=revision+1,world_revision=world_revision+1,
                        stars=stars+1,completed_count=completed_count+1,completed_at=? where id=?
                    """,result.completedCount()==5?ts(now):null,session);
            audit.record(UUID.fromString(actor.actorId()),"LEARNING_SLOT_COMPLETED",receipt,null,
                    Map.of("completedCount",state.completedCount()),Map.of("sessionId",session,"slot",slot,"starsAwarded",1));
            return json(200,result);
        });
    }

    @Transactional
    public Receipt receipt(ActorPrincipal actor,UUID session,int slot) {
        checkSlot(slot);
        sessionAccess(actor,session,false);
        var rows=jdbc.queryForList("select response::text from learning.receipt where session_id=? and slot=?",String.class,session,slot);
        if(rows.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return mapper.readValue(rows.getFirst(),Receipt.class);
    }

    @Transactional
    public Page<SessionItem> sessions(ActorPrincipal actor,UUID child,SessionStatus status,UUID cursor,int limit) {
        access.view(actor,child); pageLimit(limit);
        var rows=jdbc.query("""
                select s.*,p.day_index,p.timezone from learning.session s join learning.plan p on p.id=s.plan_id
                where p.child_id=? and (?::uuid is null or s.id>?::uuid)
                    and (?::text is null or (case when s.completed_at is null then 'ACTIVE' else 'COMPLETED' end)=?::text)
                order by s.id limit ?
                """,(rs,n)->new SessionItem(rs.getObject("id",UUID.class),rs.getObject("plan_id",UUID.class),
                        rs.getInt("day_index"),rs.getDate("local_date").toLocalDate(),rs.getString("timezone"),
                        rs.getTimestamp("completed_at")==null?SessionStatus.ACTIVE:SessionStatus.COMPLETED,
                        rs.getInt("stars"),rs.getInt("completed_count"),rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at")==null?null:rs.getTimestamp("completed_at").toInstant()),
                child,cursor,cursor,status==null?null:status.name(),status==null?null:status.name(),limit+1);
        boolean more=rows.size()>limit;
        var visible=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new Page<>(child,visible,more?visible.getLast().sessionId():null);
    }
    @Transactional
    public Page<Result> records(ActorPrincipal actor,UUID child,UUID cursor,int limit) {
        access.view(actor,child); pageLimit(limit);
        var rows=jdbc.query("""
                select r.*,m.subject from learning.receipt r join learning.plan p on p.id=r.plan_id
                join learning.plan_slot ps on ps.plan_id=r.plan_id and ps.slot=r.slot
                join learning.material m on m.id=ps.material_id
                where p.child_id=? and (?::uuid is null or r.id>?::uuid) order by r.id limit ?
                """,(rs,n)->new Result(rs.getObject("id",UUID.class),rs.getObject("session_id",UUID.class),
                        rs.getObject("plan_id",UUID.class),rs.getInt("slot"),rs.getString("subject"),
                        rs.getTimestamp("completed_at").toInstant(),1),child,cursor,cursor,limit+1);
        boolean more=rows.size()>limit;
        var visible=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new Page<>(child,visible,more?visible.getLast().receiptId():null);
    }
    @Transactional
    public LearningSummary summary(ActorPrincipal actor,UUID child,Instant start,Instant end) {
        access.view(actor,child);
        if(start==null || end==null || !start.isBefore(end) || Duration.between(start,end).compareTo(Duration.ofDays(366))>0)
            throw new ApiException(ErrorCode.INVALID_SCHEMA);
        var ids=jdbc.queryForList("""
                select r.id from learning.receipt r join learning.plan p on p.id=r.plan_id
                where p.child_id=? and r.completed_at>=? and r.completed_at<? order by r.id
                """,UUID.class,child,ts(start),ts(end));
        String hash=LearningCatalog.hash(mapper.writeValueAsString(List.of(child,start,end,ids)));
        return new LearningSummary(start,end,ids.size(),ids.size(),List.of(),List.of(),hash,
                List.of("RECOGNITION_NOT_COLLECTED","ASSISTANCE_NOT_COLLECTED","NOT_A_DIAGNOSTIC_SCORE"));
    }

    private UUID sessionAccess(ActorPrincipal actor,UUID session,boolean write) {
        guard.lockDomain();
        var children=jdbc.queryForList("select p.child_id from learning.session s join learning.plan p on p.id=s.plan_id where s.id=?",UUID.class,session);
        if(children.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        UUID child=children.getFirst();
        if(write) access.manage(actor,child); else access.view(actor,child);
        return child;
    }
    private Session sessionView(UUID session) {
        var slots=jdbc.query("""
                select ps.slot,m.id,m.scenario_version_id,r.id as receipt_id from learning.session s
                join learning.plan_slot ps on ps.plan_id=s.plan_id join learning.material m on m.id=ps.material_id
                left join learning.receipt r on r.session_id=s.id and r.slot=ps.slot where s.id=? order by ps.slot
                """,(rs,n)->new SessionSlot(rs.getInt(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                        rs.getObject(4)!=null,rs.getObject(4,UUID.class)),session);
        return jdbc.queryForObject("""
                select s.*,p.child_id,p.day_index,p.timezone from learning.session s join learning.plan p on p.id=s.plan_id where s.id=?
                """,(rs,n)->new Session(session,rs.getObject("child_id",UUID.class),rs.getObject("plan_id",UUID.class),
                        rs.getInt("day_index"),"REGULAR",rs.getDate("local_date").toLocalDate(),rs.getString("timezone"),
                        rs.getTimestamp("completed_at")==null?SessionStatus.ACTIVE:SessionStatus.COMPLETED,
                        rs.getLong("revision"),rs.getLong("world_revision"),rs.getInt("stars"),rs.getInt("completed_count"),slots),session);
    }
    private LearningCatalog.ReviewedMaterial requireMaterial(UUID plan,int slot) {
        var rows=jdbc.queryForList("""
                select m.content::text,m.content_hash,ps.content_hash as pinned_hash from learning.plan p
                join learning.plan_slot ps on ps.plan_id=p.id join learning.material m on m.id=ps.material_id
                where p.id=? and ps.slot=? and p.revoked_at is null and m.current and m.revoked_at is null and m.status='CONFIRMED'
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
    private StoredResponse json(int status,Object value) { return StoredResponse.json(mapper,status,value); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static void pageLimit(int limit) { if(limit<1 || limit>100) throw new ApiException(ErrorCode.INVALID_SCHEMA); }
    private static void checkSlot(int slot) { if(slot<1 || slot>5) throw new ApiException(ErrorCode.INVALID_SCHEMA); }
}
