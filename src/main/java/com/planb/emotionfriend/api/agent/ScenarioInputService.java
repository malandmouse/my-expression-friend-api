package com.planb.emotionfriend.api.agent;

import com.planb.emotionfriend.api.children.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.learning.LearningCurrent;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Saved caregiver facts stay reports. They are never treated as NPC memories or child observations. */
@Service
@Transactional
public class ScenarioInputService {
    private final JdbcTemplate db;private final JsonMapper json;private final LearningCurrent current;
    private final ChildService children;private final PrototypeContracts contracts;
    private final IdempotencyService idem;private final Clock clock;
    public ScenarioInputService(JdbcTemplate db,JsonMapper json,LearningCurrent current,ChildService children,
            PrototypeContracts contracts,IdempotencyService idem,Clock clock) {
        this.db=db;this.json=json;this.current=current;this.children=children;this.contracts=contracts;this.idem=idem;this.clock=clock;
    }
    public LearningCurrent.Snapshot access(ActorPrincipal actor,UUID child) {
        return current.read(new LearningCurrent.Query(actor,child,ChildAccessChecker.Capability.MANAGE,null,Set.of()));
    }
    public long scenarioRevision(UUID child) {
        return db.queryForObject("select coalesce((select revision from care.scenario_profile where child_id=?),0)",Long.class,child);
    }
    public long eventRevision(UUID child) {
        return db.queryForObject("select coalesce((select revision from care.daily_event_revision where child_id=?),0)",Long.class,child);
    }
    public long inputRevision(UUID child,long base) {return base+scenarioRevision(child)+eventRevision(child)+db.queryForObject("select count(*) from learning.receipt_path where child_id=?",Long.class,child);}
    public JsonNode settings(UUID child) {
        var rows=db.queryForList("select settings::text from care.scenario_profile where child_id=?",String.class,child);
        if(!rows.isEmpty())return json.readTree(rows.getFirst());
        return json.readTree("""
            {"ageBands":[],"caregiverObservedAt":null,"experienceNarrative":null,"emotionExpression":null,
             "understanding":{"informationAmount":null,"causeExplanation":null,"personReference":null},
             "priorityGoals":[],"expressionExamples":[],"characterPreference":"RECOMMEND"}
            """);
    }
    public Object profile(ActorPrincipal actor,UUID child) {
        var live=access(actor,child);var view=json.createObjectNode();
        view.set("profile",json.valueToTree(children.getProfile(actor,child)));view.put("pseudonym",db.queryForObject("select pseudonym from care.child where id=?",String.class,child));
        var fake=new AgentContracts.Generate(1,LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))),"BOTH",List.of("EMOTION_CONTEXT"),List.of());
        var mapped=FoundationProfileMapper.input(json,UUID.randomUUID(),child,live.profileVersion(),1,view,fake);
        enrich(mapped,child);
        return Map.of("revision",scenarioRevision(child),"baseProfileVersion",live.profileVersion(),"profile",mapped.path("childProfile"));
    }
    public StoredResponse putProfile(ActorPrincipal actor,UUID child,JsonNode body,String key) {
        contracts.validate("PutScenarioProfileP1",body);
        return idem.execute(actor,"PUT","/api/v1/children/"+child+"/scenario-profile",key,body,
            ()->access(actor,child),()->{
                var live=access(actor,child);
                if(body.path("expectedRevision").asLong()!=scenarioRevision(child)||body.path("expectedBaseProfileVersion").asLong()!=live.profileVersion())
                    throw new ApiException(ErrorCode.STALE_DRAFT);
                var settings=body.path("settings");
                for(String field:List.of("ageBands","priorityGoals","emotionExpression")) {
                    var v=settings.path(field);if(v.isArray()&&v.valueStream().map(JsonNode::asText).distinct().count()!=v.size())throw new ApiException(ErrorCode.INVALID_SCHEMA);
                }
                db.update("""
                    insert into care.scenario_profile(child_id,revision,settings,updated_at) values(?,1,?::jsonb,?)
                    on conflict(child_id) do update set revision=care.scenario_profile.revision+1,settings=excluded.settings,updated_at=excluded.updated_at
                    """,child,settings.toString(),Timestamp.from(clock.instant()));
                return StoredResponse.json(json,200,profile(actor,child));
            });
    }
    /** Keep the V1 wire stable while using server-saved expanded profile values. */
    public void enrich(JsonNode input,UUID child) {
        var s=settings(child);var target=(tools.jackson.databind.node.ObjectNode)input.path("childProfile");
        for(String field:List.of("caregiverObservedAt","experienceNarrative","emotionExpression","understanding","expressionExamples"))target.set(field,s.path(field));
        if(!s.path("ageBands").isEmpty())target.set("ageBands",s.path("ageBands"));
        if(!s.path("priorityGoals").isEmpty())target.set("priorityGoals",s.path("priorityGoals"));
        if(scenarioRevision(child)>0)target.put("characterPreference",s.path("characterPreference").asText());
        ((tools.jackson.databind.node.ObjectNode)input.path("sourceSnapshot")).put("inputRevision",inputRevision(child,input.path("sourceSnapshot").path("profileVersion").asLong()));
        ((tools.jackson.databind.node.ObjectNode)input).set("nextTraining",nextTraining(child));
        var continuity=(tools.jackson.databind.node.ObjectNode)input.path("continuity");
        var facts=continuity.putArray("knownFacts");var relations=continuity.putArray("relationships");
        var paths=db.queryForList("select receipt_id,npc_id,heard_lines::text as heard_lines from learning.receipt_path where child_id=? order by recorded_at desc,receipt_id desc limit 10",child);
        continuity.put("revision",db.queryForObject("select count(*) from learning.receipt_path where child_id=?",Long.class,child));
        for(String npc:List.of("JungHoon","MainPlayer")) {
            var refs=relations.addObject();refs.put("npcId",npc);var evidence=refs.putArray("evidenceRefs");
            paths.stream().filter(p->npc.equals(p.get("npc_id"))).forEach(p->evidence.add(p.get("receipt_id").toString()));
            refs.put("meetingState",evidence.isEmpty()?"UNKNOWN":"PRIOR_INTERACTION_CONFIRMED");
        }
        for(var path:paths) {
            // Only actually heard, approved NPC words are a memory source. No unselected branch, report or child raw text.
            String words=String.join(" ",json.readTree((String)path.get("heard_lines")).valueStream().map(JsonNode::asText).toList());
            if(words.isBlank())continue;
            var fact=facts.addObject();fact.put("factId",path.get("receipt_id").toString()).put("text",words.substring(0,words.offsetByCodePoints(0,Math.min(300,words.codePointCount(0,words.length())))))
                .put("evidenceEventId",path.get("receipt_id").toString());fact.putArray("heardByNpcIds").add((String)path.get("npc_id"));
        }
    }
    /** Only observations attached to actual completed paths can inform the next request; no automatic profile changes. */
    public JsonNode nextTraining(UUID child) {
        var rows=db.queryForList("select o.event_id,o.outcome from learning.prototype_observation o join learning.receipt r on r.session_id=o.session_id and r.slot=o.slot join learning.receipt_path p on p.receipt_id=r.id where o.child_id=? and o.type='EMOTION_ANSWERED' and o.first_response and o.support='NONE' and o.outcome in ('MATCH','MISMATCH') order by o.received_at desc limit 20",child);
        if(rows.isEmpty())return json.nullNode();
        long mismatches=rows.stream().filter(r->r.get("outcome").equals("MISMATCH")).count();boolean recap=rows.size()>=3&&mismatches>=2;
        var n=json.createObjectNode();n.putArray("priorityGoals").add(recap?"EMOTION_CAUSE":"EMOTION_CONTEXT").add("SELF_FEELING");n.put("explanationHint",recap?"RECAP_CAUSE":"MAINTAIN");
        n.putArray("reasonCodes").add(recap?"REVISIT_CAUSE_WITH_NEW_DAILY_EVENT":"CONTINUE_WITH_VARIETY");var ids=n.putArray("evidenceEventIds");rows.forEach(r->ids.add(r.get("event_id").toString()));n.putArray("repeatItemIds");n.put("profileChanged",false).put("publishedScenarioChanged",false);return n;
    }
    private void bump(UUID child) {db.update("insert into care.daily_event_revision(child_id,revision) values(?,1) on conflict(child_id) do update set revision=care.daily_event_revision.revision+1",child);}
    private JsonNode event(Map<String,Object> row) {
        var n=json.createObjectNode();n.put("eventId",row.get("id").toString()).put("version",((Number)row.get("version")).longValue())
            .put("occurredOn",row.get("occurred_on").toString()).put("text",(String)row.get("text"))
            .put("source","CAREGIVER_REPORT").put("allowedUse","CHILD_REPLY_CANDIDATE_ONLY")
            .put("createdAt",((Timestamp)row.get("created_at")).toInstant().toString()).put("updatedAt",((Timestamp)row.get("updated_at")).toInstant().toString());
        if(row.get("reported_feeling")==null)n.putNull("reportedFeeling");else n.put("reportedFeeling",(String)row.get("reported_feeling"));return n;
    }
    public Object events(ActorPrincipal actor,UUID child,UUID cursor) {
        access(actor,child);var rows=db.queryForList("select * from care.daily_event where child_id=? and deleted_at is null and (?::uuid is null or id>?::uuid) order by id limit 21",child,cursor,cursor);
        var out=json.createObjectNode();out.put("revision",eventRevision(child));var items=out.putArray("items");rows.stream().limit(20).forEach(row->items.add(event(row)));
        if(rows.size()>20)out.put("nextCursor",rows.get(19).get("id").toString());else out.putNull("nextCursor");return out;
    }
    public StoredResponse writeEvent(ActorPrincipal actor,UUID child,UUID id,JsonNode body,String key) {
        contracts.validate(id==null?"DailyEventCommandP1":"DailyEventUpdateP1",body);
        if(body.path("text").asText().isBlank())throw new ApiException(ErrorCode.INVALID_SCHEMA);
        String method=id==null?"POST":"PUT",path="/api/v1/children/"+child+"/daily-events"+(id==null?"":"/"+id);
        return idem.execute(actor,method,path,key,body,()->access(actor,child),()->{
            var now=Timestamp.from(clock.instant());UUID eventId=id==null?UUID.randomUUID():id;
            if(id==null)db.update("insert into care.daily_event(id,child_id,occurred_on,text,reported_feeling,created_at,updated_at) values(?,?,?,?,?,?,?)",eventId,child,java.sql.Date.valueOf(body.path("occurredOn").asText()),body.path("text").asText(),body.path("reportedFeeling").asText(null),now,now);
            else if(db.update("update care.daily_event set version=version+1,occurred_on=?,text=?,reported_feeling=?,updated_at=? where id=? and child_id=? and version=? and deleted_at is null",java.sql.Date.valueOf(body.path("occurredOn").asText()),body.path("text").asText(),body.path("reportedFeeling").asText(null),now,id,child,body.path("expectedVersion").asLong())!=1)throw new ApiException(ErrorCode.STALE_DRAFT);
            bump(child);return StoredResponse.json(json,id==null?201:200,event(db.queryForMap("select * from care.daily_event where id=?",eventId)));
        });
    }
    public StoredResponse deleteEvent(ActorPrincipal actor,UUID child,UUID id,long version,String key) {
        return idem.execute(actor,"DELETE","/api/v1/children/"+child+"/daily-events/"+id,key,Map.of("expectedVersion",version),()->access(actor,child),()->{
            if(db.update("update care.daily_event set deleted_at=?,updated_at=?,version=version+1 where id=? and child_id=? and version=? and deleted_at is null",Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),id,child,version)!=1)throw new ApiException(ErrorCode.STALE_DRAFT);
            bump(child);return StoredResponse.json(json,200,Map.of("deleted",true,"revision",eventRevision(child)));
        });
    }
    public List<AgentContracts.Event> selected(UUID child,JsonNode ids) {
        var result=new ArrayList<AgentContracts.Event>();
        for(var id:ids){var rows=db.queryForList("select * from care.daily_event where id=? and child_id=? and deleted_at is null",UUID.fromString(id.asText()),child);
            if(rows.isEmpty())throw new ApiException(ErrorCode.STALE_DRAFT);var row=rows.getFirst();
            result.add(new AgentContracts.Event(((java.sql.Date)row.get("occurred_on")).toLocalDate(),(String)row.get("text"),(String)row.get("reported_feeling")));}
        return result;
    }
}
