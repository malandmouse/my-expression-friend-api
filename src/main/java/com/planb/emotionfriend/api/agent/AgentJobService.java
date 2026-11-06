package com.planb.emotionfriend.api.agent;

import static com.planb.emotionfriend.api.learning.integration.GenerationJobs.*;
import com.planb.emotionfriend.api.children.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.learning.*;
import com.planb.emotionfriend.api.learning.integration.PostgresGenerationJobs;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Foundation is the source of identity/currentness/jobs/drafts. The worker cannot publish or award stars. */
@Service
public class AgentJobService implements AutoCloseable {
    private final JdbcTemplate db;private final JsonMapper json;private final LearningCurrent current;
    private final ChildService children;private final CoreManuscriptStore manuscripts;private final AgentWorkerClient worker;private final AgentEnvelopeValidator envelope;
    private final IdempotencyService idempotency;private final Clock clock;private final TransactionTemplate tx;
    private final PostgresGenerationJobs jobs;private final ScenarioInputService inputs;private final PrototypeContracts prototypes;private final PrototypeDays days;
    @Value("${emotion-friend.agent.model:gpt-5.4}") private String modelRef;
    @Value("${emotion-friend.agent.configuration-ref:prototype-st-v2.1-ko4}") private String configurationRef;
    @Value("${emotion-friend.prototype.live-enabled:false}") private boolean liveGeneration;
    private final ExecutorService executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),
        r->{var t=new Thread(r,"foundation-agent-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public AgentJobService(JdbcTemplate db,JsonMapper json,LearningCurrent current,ChildService children,
            CoreManuscriptStore manuscripts,AgentWorkerClient worker,IdempotencyService idempotency,
            Clock clock,PlatformTransactionManager manager,AgentEnvelopeValidator envelope,ScenarioInputService inputs,PrototypeContracts prototypes,PrototypeDays days) {
        this.db=db;this.json=json;this.current=current;this.children=children;this.manuscripts=manuscripts;
        this.worker=worker;this.envelope=envelope;this.idempotency=idempotency;this.clock=clock;this.tx=new TransactionTemplate(manager);
        this.jobs=new PostgresGenerationJobs(db,manager,clock);this.inputs=inputs;this.prototypes=prototypes;this.days=days;
    }
    private LearningCurrent.Query query(ActorPrincipal actor,UUID child) {
        return new LearningCurrent.Query(actor,child,ChildAccessChecker.Capability.MANAGE,null,
            Set.of(ChildContracts.ConsentScope.CORE_PROFILE_LEARNING));
    }
    private Snapshot source(UUID id) {
        return db.query("select actor_json::text,snapshot_json::text,input_json::text,settings_ref from learning.agent_source where id=?",
            (rs,n)->new Snapshot(actorPin(json.readTree(rs.getString(1))),
                json.readValue(rs.getString(2),LearningCurrent.Snapshot.class),json.readTree(rs.getString(3)),rs.getObject(4,UUID.class)),id)
            .stream().findFirst().orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private record Snapshot(ActorPrincipal actor,LearningCurrent.Snapshot pin,JsonNode input,UUID settings) {}
    private ActorPrincipal actorPin(JsonNode pin){return new ActorPrincipal(pin.path("actorId").asText(),ActorPrincipal.Kind.ADULT,null,null,null,pin.path("sessionId").asText(),false);}
    private String actorJson(ActorPrincipal actor){return json.createObjectNode().put("actorId",actor.actorId()).put("sessionId",actor.sessionId()).toString();}
    private Access guard(Request request) {
        current.lockDomain();
        Snapshot s=source(request.input().sourceRef());
        if(!s.actor().actorId().equals(request.scope().requesterId().toString()) || !s.pin().childId().equals(request.scope().childId())
                || !request.input().settingsRef().equals(s.settings()) || request.input().inputRevision()!=s.input().path("sourceSnapshot").path("inputRevision").asLong() || request.input().authorityRevision()!=s.input().path("sourceSnapshot").path("authorityRevision").asLong())return Access.STALE;
        try{var now=current.read(query(s.actor(),s.pin().childId()));boolean frozen=Boolean.TRUE.equals(db.queryForObject("select frozen_profile from learning.agent_source where id=?",Boolean.class,request.input().sourceRef()));
            return (frozen?sameAuthority(now,s.pin()):now.equals(s.pin())&&request.input().inputRevision()==inputs.inputRevision(s.pin().childId(),now.profileVersion()))?Access.CURRENT:Access.STALE;}
        catch(ApiException denied){return Access.REVOKED;}
    }
    public static boolean sameAuthority(LearningCurrent.Snapshot a,LearningCurrent.Snapshot b){return a.childId().equals(b.childId())&&a.guardianId().equals(b.guardianId())&&Objects.equals(a.therapistInvitationId(),b.therapistInvitationId())&&Objects.equals(a.deviceGeneration(),b.deviceGeneration())&&a.consentRevisions().equals(b.consentRevisions());}
    public StoredResponse reserve(ActorPrincipal actor,UUID child,AgentContracts.Generate body,String key) {
        worker.requireEnabled();
        return idempotency.execute(actor,"POST","/api/v1/children/"+child+"/agent-jobs",key,body,
            ()->current.read(query(actor,child)),()->{
                var live=current.read(query(actor,child));
                var view=json.createObjectNode();view.set("profile",json.valueToTree(children.getProfile(actor,child)));
                view.put("pseudonym",db.queryForObject("select pseudonym from care.child where id=?",String.class,child));
                // Include verified live input and requester in the semantic fingerprint, not a browser-supplied snapshot.
                var binding=json.writeValueAsBytes(canonical(json.valueToTree(List.of(actor.actorId(),actor.sessionId(),child,live,body,inputs.inputRevision(child,live.profileVersion()),configurationRef,modelRef))));
                UUID source=UUID.nameUUIDFromBytes(binding),settings=UUID.nameUUIDFromBytes(json.writeValueAsBytes(body));
                long authority=live.consentRevisions().get(ChildContracts.ConsentScope.CORE_PROFILE_LEARNING);
                JsonNode input=FoundationProfileMapper.input(json,source,child,live.profileVersion(),authority,view,body);
                inputs.enrich(input,child);if(worker.statefulEnabled())((tools.jackson.databind.node.ObjectNode)input).put("configurationRef",configurationRef);
                db.update("""
                    insert into learning.agent_source(id,child_id,requester_id,authority_revision,settings_ref,actor_json,snapshot_json,input_json)
                    values(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb) on conflict(id) do nothing
                    """,source,child,UUID.fromString(actor.actorId()),authority,settings,actorJson(actor),json.writeValueAsString(live),input.toString());
                var metadata=json.createObjectNode().put("modelRef",modelRef).put("configurationRef",configurationRef).put("sourceSnapshotHash",PrototypeJson.digest(input)).put("generationMode",liveGeneration?"LIVE":"MOCK");
                db.update("update learning.agent_source set input_metadata=?::jsonb where id=? and input_metadata is null",metadata.toString(),source);
                var request=new Request(scope(actor,child),new Input(source,input.path("sourceSnapshot").path("inputRevision").asLong(),authority,settings,null));
                var job=jobs.reserve(request,clock.instant().plusSeconds(worker.statefulEnabled()?1800:300),(r,now)->new Fingerprint(CoreDependencyValidator.sha(binding),null),this::guard);
                return StoredResponse.json(json,202,view(job));
            });
    }
    @org.springframework.transaction.annotation.Transactional
    public StoredResponse reserveP1(ActorPrincipal actor,UUID child,JsonNode body,String key) {
        prototypes.validate("GenerateDayCommandP1",body);
        // 무료 확인 도구/웹이 조회한 실행 모드를 요청에 묶습니다. 서버 모드가 바뀌면 과금 전에 거부합니다.
        if(body.has("expectedGenerationMode")&&!body.path("expectedGenerationMode").asText().equals(liveGeneration?"LIVE":"MOCK"))throw new ApiException(ErrorCode.EXECUTION_MODE_MISMATCH);
        var live=inputs.access(actor,child);var localDate=LocalDate.parse(body.path("localDate").asText());
        if(!localDate.equals(days.today())||body.path("programDay").asInt()!=days.nextDay(child,localDate)||body.path("expectedProfileVersion").asLong()!=live.profileVersion()||body.path("expectedScenarioProfileRevision").asLong()!=inputs.scenarioRevision(child)||body.path("expectedDailyEventRevision").asLong()!=inputs.eventRevision(child))throw new ApiException(ErrorCode.STALE_DRAFT);
        var settings=inputs.settings(child);var goals=settings.path("priorityGoals").valueStream().map(JsonNode::asText).toList();if(goals.isEmpty())goals=List.of("EMOTION_CONTEXT","SELF_FEELING");
        String npc=settings.path("characterPreference").asText();if(npc.equals("RECOMMEND"))npc="BOTH";
        var response=reserve(actor,child,new AgentContracts.Generate(body.path("programDay").asInt(),localDate,npc,goals,inputs.selected(child,body.path("dailyEventIds"))),key);
        UUID jobId=UUID.fromString(json.readTree(response.body()).path("jobId").asText());days.bind(child,localDate,body.path("programDay").asInt(),jobId);
        var source=db.queryForMap("select id,input_metadata::text from learning.agent_source where id=(select source_ref from learning.generation_job where id=?)",jobId);var metadata=(tools.jackson.databind.node.ObjectNode)json.readTree((String)source.get("input_metadata"));
        metadata.put("profileVersion",live.profileVersion()).put("scenarioProfileRevision",inputs.scenarioRevision(child)).put("dailyEventRevision",inputs.eventRevision(child));metadata.set("consentRevisions",json.valueToTree(live.consentRevisions()));
        var selected=metadata.putArray("selectedDailyEvents");for(var event:body.path("dailyEventIds")){var e=db.queryForMap("select id,version from care.daily_event where child_id=? and id=? and deleted_at is null",child,UUID.fromString(event.asText()));selected.addObject().put("eventId",e.get("id").toString()).put("version",((Number)e.get("version")).longValue());}
        db.update("update learning.agent_source set frozen_profile=true,input_metadata=?::jsonb where id=?",metadata.toString(),source.get("id"));return response;
    }
    @org.springframework.transaction.annotation.Transactional public Object list(ActorPrincipal actor,UUID child) {
        current.read(query(actor,child));return Map.of("items",db.queryForList("select j.id as \"jobId\",j.state,j.version,j.deadline,coalesce(r.mode,s.input_metadata->>'generationMode','MOCK') as mode from learning.generation_job j join learning.agent_source s on s.id=j.source_ref left join learning.agent_run r on r.job_id=j.id where s.child_id=? and s.requester_id=? order by j.created_at desc limit 20",child,UUID.fromString(actor.actorId())));
    }
    @org.springframework.transaction.annotation.Transactional public Object capabilities(ActorPrincipal actor,UUID child) {
        current.read(query(actor,child));worker.requireEnabled();
        JsonNode actual;
        try{actual=worker.runtimeStatus();}catch(AgentWorkerClient.WorkerRejected|AgentWorkerClient.UnknownOutcome unavailable){throw new ApiException(ErrorCode.WORKER_UNAVAILABLE);}
        // 같은 V2.1 엔진이라도 집필 정책이 다르면 제작 가능 상태로 알리지 않습니다.
        if(!actual.path("mode").asText().equals(liveGeneration?"LIVE":"MOCK")||!actual.path("engine").asText().equals("EF_ST_AGENT_DRAFT_V2_1")||!actual.path("configurationRef").asText().equals(configurationRef))throw new ApiException(ErrorCode.EXECUTION_MODE_MISMATCH);
        return Map.of("schemaVersion","EF_AGENT_CAPABILITIES_P1","childId",child,
            "generationMode",liveGeneration?"LIVE":"MOCK","engine",worker.statefulEnabled()?"EF_ST_AGENT_DRAFT_V2_1":"EF_FOUNDATION_AGENT_1",
            "modelRef",modelRef,"configurationRef",configurationRef,"sessionsPerDay",5,
            "expectedGptCalls",worker.statefulEnabled()?41:0);
    }
    /** Called only after HTTP idempotency transaction has committed. Queued jobs survive process restart. */
    public void enqueue(UUID id) {try{executor.execute(()->{try{dispatch(id);}catch(RuntimeException unavailable){/* persisted job is recovered by explicit status/dispatch; never log private inputs */}});}catch(RejectedExecutionException full){/* remains QUEUED; explicit dispatch can resume */}}
    private Scope scope(ActorPrincipal actor,UUID child){return new Scope(child,UUID.fromString(actor.actorId()),Kind.AUTHORING);}
    public Object status(ActorPrincipal actor,UUID child,UUID id) {
        return view(jobs.status(id,scope(actor,child),this::guard).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND)));
    }
    public Object cancel(ActorPrincipal actor,UUID child,UUID id,long version) {
        return view(jobs.cancel(id,scope(actor,child),version,this::guard));
    }
    public Object summaries(ActorPrincipal actor,UUID child,UUID id) {
        return tx.execute(status->{
            var job=jobs.status(id,scope(actor,child),this::guard).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            if(job.state()!=State.RESULT_READY)throw new ApiException(ErrorCode.MATERIAL_NOT_READY);
            var row=db.queryForMap("select bytes_hash,result_json,summary_refs::text as summary_refs from learning.agent_artifact where id=? and source_ref=?",job.artifact().id(),job.request().input().sourceRef());
            if(!job.artifact().bytesHash().equals(row.get("bytes_hash")) || !job.artifact().bytesHash().equals(CoreDependencyValidator.sha(((String)row.get("result_json")).getBytes(StandardCharsets.UTF_8))))
                throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
            return Map.of("job",view(job),"summaryRefs",json.readTree((String)row.get("summary_refs")),
                "mode",json.readTree((String)row.get("result_json")).path("mode").asText(),"publicationAllowed",false,"dependenciesStatus","AWAITING_SUMMARY_APPROVAL",
                "webPreview",json.readTree((String)row.get("result_json")).path("result").path("webPreview"),"dayDraftHash",PrototypeReleaseService.draftHash(json,json.readTree((String)row.get("result_json")),source(job.request().input().sourceRef()).input()));
        });
    }
    public void dispatchAuthorized(ActorPrincipal actor,UUID child,UUID id){var job=jobs.status(id,scope(actor,child),this::guard).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND));if(worker.statefulEnabled()&&job.state()==State.RUNNING)executor.execute(()->dispatchClaim(id,job));else enqueue(id);}
    void dispatch(UUID id) {
        var maybe=jobs.claim(id,Kind.AUTHORING,this::guard);if(maybe.isEmpty())return;dispatchClaim(id,maybe.get());
    }
    private void dispatchClaim(UUID id,Job claim) {
        JsonNode response;
        try {
            var input=(tools.jackson.databind.node.ObjectNode)source(claim.request().input().sourceRef()).input().deepCopy();
            input.put("jobId",id.toString());response=worker.execute("/internal/v1/daily-scenarios",input);
        }catch(AgentWorkerClient.WorkerRejected known){jobs.settle(claim,new Result(State.FAILED,null),this::guard);return;}
        catch(RuntimeException unknown){jobs.settle(claim,new Result(State.OUTCOME_UNKNOWN,null),this::guard);return;}
        try {
            envelope.validate(response.toString());
            tx.executeWithoutResult(status->{
                current.lockDomain();if(guard(claim.request())!=Access.CURRENT)return;
                // Admission and settlement share a transaction/fence. Cancelled, expired or late results create no summaries.
                var rows=db.queryForList("select state,version,deadline from learning.generation_job where id=? for update",id);
                if(rows.isEmpty())return;var row=rows.getFirst();
                if(!"RUNNING".equals(row.get("state")) || ((Number)row.get("version")).longValue()!=claim.version()
                        || !clock.instant().isBefore(((java.sql.Timestamp)row.get("deadline")).toInstant()))return;
                boolean v2=response.path("contractVersion").asText().equals("EF_FOUNDATION_AGENT_2")&&worker.statefulEnabled();
                if(!(v2||response.path("contractVersion").asText().equals("EF_FOUNDATION_AGENT_1")) || !response.path("jobId").asText().equals(id.toString())
                        || !(v2?Set.of("MOCK","LIVE").contains(response.path("mode").asText()):response.path("mode").asText().equals("MOCK")&&response.path("paidCalls").asInt(-1)==0) || response.path("publicationAllowed").asBoolean(true)
                        || response.path("coreDrafts").size()!=5 || response.path("result").path("scenarios").size()!=5
                        || !response.path("sourceSnapshot").equals(source(claim.request().input().sourceRef()).input().path("sourceSnapshot")))
                    throw new IllegalArgumentException("Invalid worker binding");
                var refs=json.createArrayNode();var slots=new HashSet<Integer>();
                for(var draft:response.path("coreDrafts")) {
                    int slot=draft.path("slot").asInt();if(slot<1||slot>5||!slots.add(slot))throw new IllegalArgumentException("Invalid slot set");
                    UUID material=UUID.fromString(draft.path("materialId").asText());
                    UUID summary=manuscripts.store(claim.request().scope().childId(),new CoreManuscriptStore.Draft(material,
                        UUID.fromString(draft.path("scenarioVersionId").asText()),"st:"+id+":"+slot,
                        draft.path("subject").asText(),draft.path("summary").asText(),draft.path("payload").toString(),CoreScenarioValidator.Profile.GENERATED_1_TO_3));
                    refs.addObject().put("slot",slot).put("materialId",material.toString()).put("summaryId",summary.toString());
                }
                String text=response.toString(),hash=CoreDependencyValidator.sha(text.getBytes(StandardCharsets.UTF_8));UUID artifact=UUID.randomUUID();
                db.update("insert into learning.agent_artifact values(?,?,?, ?,?::jsonb,now())",artifact,claim.request().input().sourceRef(),hash,text,refs.toString());
                if(!jobs.settle(claim,new Result(State.RESULT_READY,new Artifact(artifact,hash)),this::guard))throw new IllegalStateException("Claim changed");
            });
        }catch(RuntimeException invalid){jobs.settle(claim,new Result(State.HELD,null),this::guard);}
    }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()){var out=json.createObjectNode();node.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(e->out.set(e.getKey(),canonical(e.getValue())));return out;}
        if(node.isArray()){var out=json.createArrayNode();node.forEach(n->out.add(canonical(n)));return out;}return node;
    }
    private Map<String,Object> view(Job job) {
        return Map.of("jobId",job.id(),"state",job.state(),"version",job.version(),"deadline",job.deadline(),
            "mode",mode(job.id()),"paidCalls",db.queryForObject("select count(*) from learning.agent_call where job_id=? and mode='LIVE'",Long.class,job.id()),"publicationAllowed",false);
    }
    private String mode(UUID id){return db.queryForObject("select coalesce(r.mode,s.input_metadata->>'generationMode','MOCK') from learning.generation_job j join learning.agent_source s on s.id=j.source_ref left join learning.agent_run r on r.job_id=j.id where j.id=?",String.class,id);}
    @Override public void close(){executor.shutdown();}
}
