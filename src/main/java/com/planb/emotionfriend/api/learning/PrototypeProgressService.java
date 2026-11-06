package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.agent.*;
import com.planb.emotionfriend.api.children.ChildContracts.ConsentScope;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 노출/첫 응답과 완료 원장을 분리한다. 자기 감정·모름·오류는 정오답 분모에 넣지 않는다. */
@Service @Transactional
public class PrototypeProgressService {
    private final JdbcTemplate db;private final JsonMapper json;private final LearningCurrent current;private final PrototypeContracts contracts;private final Clock clock;private final RuntimeLearningService runtime;private final ScenarioInputService inputs;
    public PrototypeProgressService(JdbcTemplate db,JsonMapper json,LearningCurrent current,PrototypeContracts contracts,Clock clock,RuntimeLearningService runtime,ScenarioInputService inputs){this.db=db;this.json=json;this.current=current;this.contracts=contracts;this.clock=clock;this.runtime=runtime;this.inputs=inputs;}
    public Object cursor(ActorPrincipal actor,UUID session){runtime.sessionAccess(actor,session);var cp=json.readTree(db.queryForObject("select body::text from learning.runtime_checkpoint where session_id=?",String.class,session));return Map.of("sequence",db.queryForObject("select coalesce(max(sequence),0) from learning.prototype_observation where session_id=? and attempt_id=?",Long.class,session,UUID.fromString(cp.path("attemptId").asText())));}
    public Object observations(ActorPrincipal actor,UUID session,JsonNode body) {
        contracts.validate("ObservationBatchP1",body);runtime.sessionAccess(actor,session);var accepted=json.createArrayNode();
        for(var e:body.path("events")) {
            int slot=e.path("slot").asInt();UUID event=UUID.fromString(e.path("eventId").asText());String hash=PrototypeJson.digest(e);
            var old=db.queryForList("select request_hash,session_id from learning.prototype_observation where event_id=?",event);
            if(!old.isEmpty()){if(!old.getFirst().get("session_id").equals(session)||!hash.equals(old.getFirst().get("request_hash")))throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);accepted.add(event.toString());continue;}
            var p=current.read(new LearningCurrent.Query(actor,UUID.fromString(actor.childId()),null,new LearningCurrent.SessionSlot(session,slot),Set.of(ConsentScope.CORE_PROFILE_LEARNING))).progress();
            if(!p.attemptId().toString().equals(e.path("attemptId").asText())||!p.scenarioVersionId().toString().equals(e.path("scenarioVersionId").asText()))throw new ApiException(ErrorCode.STALE_CHECKPOINT);
            long sequence=db.queryForObject("select coalesce(max(sequence),0)+1 from learning.prototype_observation where session_id=? and attempt_id=?",Long.class,session,p.attemptId());
            if(sequence!=e.path("sequence").asLong())throw new ApiException(ErrorCode.EVENT_SEQUENCE_GAP);
            var manuscript=json.readTree(db.queryForObject("select payload from learning.core_manuscript where scenario_version_id=?",String.class,p.scenarioVersionId()));
            var cp=json.readTree(db.queryForObject("select body::text from learning.runtime_checkpoint where session_id=?",String.class,session));
            String type=e.path("type").asText(),node=e.path("nodeId").asText(null),emotion=e.path("selectedEmotionId").asText(null),question=e.path("questionId").asText(null),outcome="UNSCORED",support="UNKNOWN";boolean first=false,presented=false;
            if(node!=null&&!node.equals(cp.path("sceneId").asText(null))&&!node.equals(p.terminalSceneId()))throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);
            if(type.equals("CHOICE_SELECTED")&&!p.choiceIds().contains(e.path("choiceId").asText()))throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);
            if(Set.of("EMOTION_QUESTION_SHOWN","EMOTION_ANSWERED","REPLAY_USED").contains(type)) {
                if(p.terminalSceneId()==null||!p.terminalSceneId().equals(question))throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);
                presented=type.equals("EMOTION_QUESTION_SHOWN")||Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from learning.prototype_observation where session_id=? and attempt_id=? and type='EMOTION_QUESTION_SHOWN' and question_id=?)",Boolean.class,session,p.attemptId(),question));
                long replay=db.queryForObject("select count(*) from learning.prototype_observation where session_id=? and attempt_id=? and type='REPLAY_USED' and question_id=?",Long.class,session,p.attemptId(),question);support=presented?(replay>0?"REPLAY":"NONE"):"UNKNOWN";
                if(type.equals("EMOTION_ANSWERED")) {
                    var q=manuscript.path("emotionQuestionByTerminal").valueStream().filter(v->v.path("terminalSceneId").asText().equals(question)).findFirst().orElseThrow(()->new ApiException(ErrorCode.INVALID_BRANCH_PATH));
                    if(!Objects.equals(emotion,p.emotionId())||q.path("options").valueStream().noneMatch(o->o.path("emotionId").asText().equals(emotion)))throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);
                    first=!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from learning.prototype_observation where session_id=? and attempt_id=? and type='EMOTION_ANSWERED' and question_id=?)",Boolean.class,session,p.attemptId(),question));
                    if(presented&&!emotion.equals("UNKNOWN"))outcome=q.path("acceptedEmotionIds").valueStream().anyMatch(v->v.asText().equals(emotion))?"MATCH":"MISMATCH";
                }
            }else if(type.equals("NPC_NODE_SHOWN"))presented=node!=null;
            db.update("insert into learning.prototype_observation values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",event,UUID.fromString(actor.childId()),session,p.attemptId(),slot,sequence,p.scenarioVersionId(),type,node,question,e.path("choiceId").asText(null),emotion,presented,first,support,outcome,hash,Timestamp.from(Instant.parse(e.path("occurredAt").asText())),Timestamp.from(clock.instant()));accepted.add(event.toString());
        }
        var out=json.createObjectNode();out.set("acceptedEventIds",accepted);out.put("observationRevision",db.queryForObject("select count(*) from learning.prototype_observation where child_id=?",Long.class,UUID.fromString(actor.childId())));return out;
    }
    public Object dashboard(ActorPrincipal actor,UUID child,LocalDate date) {
        current.read(new LearningCurrent.Query(actor,child,ChildAccessChecker.Capability.VIEW_SUMMARY,null,Set.of(ConsentScope.CORE_PROFILE_LEARNING)));
        var days=db.queryForList("select * from learning.prototype_day where child_id=? and local_date=?",child,java.sql.Date.valueOf(date));if(days.isEmpty())throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);var day=days.getFirst();
        var out=json.createObjectNode().put("schemaVersion","EF_DASHBOARD_P1").put("childId",child.toString()).put("localDate",date.toString()).put("programDay",((Number)day.get("program_day")).intValue());var slots=out.putArray("slots");int completed=0,stars=0,original=0,composed=0;
        if(day.get("plan_id")!=null)for(var row:db.queryForList("select ps.slot,m.subject,r.id,r.completed_at,r.response::text,sa.amount from learning.plan_slot ps join learning.material m on m.id=ps.material_id left join learning.receipt r on r.plan_id=ps.plan_id and r.slot=ps.slot left join learning.star_award sa on sa.receipt_id=r.id where ps.plan_id=? order by ps.slot",day.get("plan_id"))) {
            var slot=slots.addObject().put("slot",((Number)row.get("slot")).intValue()).put("title",(String)row.get("subject"));boolean done=row.get("id")!=null;
            boolean active=Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from learning.session where plan_id=? and completed_count+1=? and completed_at is null)",Boolean.class,day.get("plan_id"),row.get("slot")));
            slot.put("status",done?"COMPLETED":active?"IN_PROGRESS":"NOT_STARTED");if(done){completed++;stars+=((Number)row.get("amount")).intValue();slot.put("receiptId",row.get("id").toString()).put("completedAt",((Timestamp)row.get("completed_at")).toInstant().toString());if(json.readTree((String)row.get("response")).path("expressionChoice").asText().equals("AI_COMPOSED"))composed++;else original++;}else {slot.putNull("receiptId");slot.putNull("completedAt");}
        }
        out.put("completedCount",completed).put("stars",stars);var counts=db.queryForMap("select count(*) as all_count,count(*) filter(where o.type='EMOTION_ANSWERED' and o.first_response and o.support='NONE' and o.outcome in ('MATCH','MISMATCH')) as valid,count(*) filter(where o.type='EMOTION_ANSWERED' and o.first_response and o.support='NONE' and o.outcome='MATCH') as correct,count(*) filter(where o.type='REPLAY_USED') as replay,count(*) filter(where o.type='EMOTION_ANSWERED' and o.support='UNKNOWN') as unknown from learning.prototype_observation o join learning.session s on s.id=o.session_id where s.plan_id=?",day.get("plan_id"));
        var recognition=out.putObject("recognition");if(((Number)counts.get("all_count")).longValue()==0)for(String k:List.of("validFirstResponses","correctFirstResponses","replayUsed","unknownSupportConditions"))recognition.putNull(k);else recognition.put("validFirstResponses",((Number)counts.get("valid")).longValue()).put("correctFirstResponses",((Number)counts.get("correct")).longValue()).put("replayUsed",((Number)counts.get("replay")).longValue()).put("unknownSupportConditions",((Number)counts.get("unknown")).longValue());
        out.putObject("expression").put("originalCount",original).put("aiComposedCount",composed).put("selfFeelingScoring","UNSCORED");out.putArray("limitations").add("관찰 누락은 0점이 아닙니다. 자기 감정은 채점하지 않으며 진단 점수가 아닙니다.");out.set("nextTraining",inputs.nextTraining(child));out.put("observationRevision",db.queryForObject("select count(*) from learning.prototype_observation where child_id=?",Long.class,child));contracts.validate("DashboardP1",out);return out;
    }
}
