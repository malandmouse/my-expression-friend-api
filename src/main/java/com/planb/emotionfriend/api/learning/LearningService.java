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
    private final LearningLedger ledger;
    private final Clock clock;
    public LearningService(JdbcTemplate jdbc,JsonMapper mapper,LearningAccess access,MemberGuard guard,
            AuditTrail audit,IdempotencyService idempotency,LearningLedger ledger,Clock clock) {
        this.jdbc=jdbc;this.mapper=mapper;this.access=access;this.guard=guard;this.audit=audit;
        this.idempotency=idempotency;this.ledger=ledger;this.clock=clock;
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
            var started=ledger.start(child,request);
            return json(started.status(),started.session());
        });
    }
    @Transactional
    public Session session(ActorPrincipal actor,UUID session) {
        sessionAccess(actor,session,false);
        return ledger.session(session);
    }
    public StoredResponse expression(ActorPrincipal actor,UUID session,int slot,ExpressionRequest request,String key) {
        checkSlot(slot);
        return idempotency.execute(actor,"POST","/api/v1/learning-sessions/"+session+"/slots/"+slot+"/confirmed-expressions",key,request,
                ()->sessionAccess(actor,session,true),
                ()->json(201,ledger.expression(session,slot,actor.actorId(),request.draftVersion())));
    }
    public StoredResponse complete(ActorPrincipal actor,UUID session,int slot,FinalizeRequest request,String key) {
        checkSlot(slot);
        return idempotency.execute(actor,"POST","/api/v1/learning-sessions/"+session+"/slots/"+slot+"/finalize",key,request,
                ()->sessionAccess(actor,session,true),()->json(200,ledger.complete(session,slot,request,actor.actorId())));
    }
    @Transactional
    public Receipt receipt(ActorPrincipal actor,UUID session,int slot) {
        checkSlot(slot);sessionAccess(actor,session,false);
        return ledger.receipt(session,slot);
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
    private StoredResponse json(int status,Object value) { return StoredResponse.json(mapper,status,value); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static void pageLimit(int limit) { if(limit<1 || limit>100) throw new ApiException(ErrorCode.INVALID_SCHEMA); }
    private static void checkSlot(int slot) { if(slot<1 || slot>5) throw new ApiException(ErrorCode.INVALID_SCHEMA); }
}
