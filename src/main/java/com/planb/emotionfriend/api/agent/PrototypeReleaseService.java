package com.planb.emotionfriend.api.agent;

import com.planb.emotionfriend.api.children.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.learning.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Approve exactly one frozen day, then synthesize outside transactions. READY publishes all five atomically. */
@Service
public class PrototypeReleaseService implements AutoCloseable {
    private final JdbcTemplate db;private final JsonMapper json;private final ScenarioInputService inputs;
    private final LearningCurrent current;private final PrototypeContracts contracts;private final IdempotencyService idem;
    private final LearningService learning;private final CorePublicationService content;private final PrototypeBinding binding;
    private final AgentWorkerClient worker;private final Clock clock;private final TransactionTemplate tx;private final boolean enabled;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"prototype-media");t.setDaemon(true);return t;});
    public PrototypeReleaseService(JdbcTemplate db,JsonMapper json,ScenarioInputService inputs,LearningCurrent current,PrototypeContracts contracts,
        IdempotencyService idem,LearningService learning,CorePublicationService content,PrototypeBinding binding,AgentWorkerClient worker,Clock clock,PlatformTransactionManager manager,
        @Value("${emotion-friend.prototype.publication-enabled:false}") boolean enabled) {
        this.db=db;this.json=json;this.inputs=inputs;this.current=current;this.contracts=contracts;this.idem=idem;this.learning=learning;this.content=content;this.binding=binding;this.worker=worker;this.clock=clock;this.tx=new TransactionTemplate(manager);this.enabled=enabled;
    }
    private LearningCurrent.Snapshot access(ActorPrincipal actor,UUID child){return current.read(new LearningCurrent.Query(actor,child,ChildAccessChecker.Capability.MANAGE,null,Set.of(ChildContracts.ConsentScope.CORE_PROFILE_LEARNING)));}
    private Map<String,Object> release(UUID id){var rows=db.queryForList("select * from learning.prototype_release where id=?",id);if(rows.isEmpty())throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);return rows.getFirst();}
    private ActorPrincipal actor(Map<String,Object> r){var p=json.readTree(r.get("actor_pin").toString());return new ActorPrincipal(p.path("actorId").asText(),ActorPrincipal.Kind.ADULT,null,null,null,p.path("sessionId").asText(),false);}
    private Map<String,Object> live(UUID id) {
        current.lockDomain();var r=release(id);var pin=access(actor(r),(UUID)r.get("child_id"));
        if(((Number)r.get("consent_revision")).longValue()!=pin.consentRevisions().get(ChildContracts.ConsentScope.CORE_PROFILE_LEARNING)||!pin.guardianId().equals(r.get("approved_by")))throw new ApiException(ErrorCode.CONSENT_REQUIRED);return r;
    }
    private JsonNode artifact(UUID job) {
        var rows=db.queryForList("select a.result_json from learning.generation_job j join learning.agent_artifact a on a.id=j.artifact_id where j.id=? and j.state='RESULT_READY'",String.class,job);
        if(rows.isEmpty())throw new ApiException(ErrorCode.MATERIAL_NOT_READY);return json.readTree(rows.getFirst());
    }
    public static String draftHash(JsonMapper json,JsonNode artifact,JsonNode input) {
        var data=json.createObjectNode();data.set("input",input);data.set("scenarios",artifact.path("result").path("scenarios"));data.set("cardVersions",artifact.path("result").path("provenance").path("cardVersions"));return PrototypeJson.digest(data);
    }
    private JsonNode source(UUID job){return json.readTree(db.queryForObject("select s.input_json::text from learning.generation_job j join learning.agent_source s on s.id=j.source_ref where j.id=?",String.class,job));}
    @Transactional public StoredResponse decide(ActorPrincipal actor,UUID child,UUID job,JsonNode body,String key) {
        contracts.validate("DayDecisionP1",body);
        return idem.execute(actor,"POST","/api/v1/children/"+child+"/agent-jobs/"+job+"/day-decision",key,body,()->access(actor,child),()->{
            if(!enabled)throw new ApiException(ErrorCode.CONTENT_NOT_APPROVED);var pin=access(actor,child);
            var row=db.queryForMap("select j.version,d.release_id from learning.generation_job j join learning.prototype_day d on d.job_id=j.id where j.id=? and d.child_id=? for update of d",job,child);
            var a=artifact(job);String hash=draftHash(json,a,source(job));
            var refs=json.readTree(db.queryForObject("select summary_refs::text from learning.agent_artifact where id=(select artifact_id from learning.generation_job where id=?)",String.class,job));
            var ids=refs.valueStream().map(n->n.path("summaryId").asText()).toList();var requested=body.path("summaryIds").valueStream().map(JsonNode::asText).toList();
            if(((Number)row.get("version")).longValue()!=body.path("expectedJobVersion").asLong()||!hash.equals(body.path("dayDraftHash").asText())||!new HashSet<>(ids).equals(new HashSet<>(requested)))throw new ApiException(ErrorCode.STALE_DRAFT);
            boolean confirmed=body.path("decision").asText().equals("CONFIRM");if(confirmed&&!body.path("childUseAllowed").asBoolean())throw new ApiException(ErrorCode.CONSENT_REQUIRED);
            UUID id=row.get("release_id")==null?UUID.randomUUID():(UUID)row.get("release_id");
            if(row.get("release_id")!=null&&!release(id).get("state").equals("HELD"))throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
            learning.confirm(actor,child,new LearningContracts.ConfirmationRequest(requested.stream().map(UUID::fromString).toList(),confirmed?LearningContracts.Decision.CONFIRM:LearningContracts.Decision.HOLD),"release:"+id+":"+body.path("decision").asText());
            var actorPin=json.createObjectNode().put("actorId",actor.actorId()).put("sessionId",actor.sessionId());
            db.update("""
                insert into learning.prototype_release(id,child_id,job_id,state,draft_hash,approved_by,consent_revision,child_use_allowed,summary_ids,actor_pin,mode,approved_at,updated_at)
                values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?) on conflict(id) do update set revision=learning.prototype_release.revision+1,state=excluded.state,
                child_use_allowed=excluded.child_use_allowed,actor_pin=excluded.actor_pin,approved_at=excluded.approved_at,updated_at=excluded.updated_at
                """,id,child,job,confirmed?"CONFIRMED_MEDIA_PENDING":"HELD",hash,UUID.fromString(actor.actorId()),pin.consentRevisions().get(ChildContracts.ConsentScope.CORE_PROFILE_LEARNING),body.path("childUseAllowed").asBoolean(),body.path("summaryIds").toString(),actorPin.toString(),a.path("mode").asText(),ts(),ts());
            db.update("update learning.prototype_day set release_id=? where job_id=?",id,job);
            if(confirmed)prepareSentences(id,a);
            return StoredResponse.json(json,202,view(id));
        });
    }
    private void prepareSentences(UUID release,JsonNode artifact) {
        for(var scenario:artifact.path("result").path("scenarios"))for(var n:scenario.path("draft").path("nodes")) {
            int number=0;for(String words:sentences(n.path("text").asText())) {
                String sentence=n.path("nodeId").asText()+"-s"+(++number),key=scenario.path("slot").asInt()+":"+sentence;
                db.update("""
                    insert into learning.prototype_media(id,release_id,sentence_key,slot,scene_id,sentence_id,sentence_index,text,text_hash,actor_id,emotion,intensity,face_cue,animation_cue,state,mode)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?) on conflict(release_id,sentence_key) do nothing
                    """,UUID.randomUUID(),release,key,scenario.path("slot").asInt(),n.path("nodeId").asText(),sentence,number,words,PrototypeJson.textHash(words),scenario.path("draft").path("npcId").asText().equals("JungHoon")?"jun":"nara",n.path("emotionId").asText(),n.path("emotionIntensity").asDouble(),n.path("faceCue").asText(),n.path("animationCue").asText(),artifact.path("mode").asText());
            }
        }
    }
    public static List<String> sentences(String text) {
        var out=new ArrayList<String>();int start=0;
        for(int i=0;i<text.length();i++)if(".!?。！？".indexOf(text.charAt(i))>=0&&(i+1==text.length()||Character.isWhitespace(text.charAt(i+1)))) {
            int end=i+1;while(end<text.length()&&Character.isWhitespace(text.charAt(end)))end++;out.add(text.substring(start,end));start=end;i=end-1;
        }
        if(start<text.length())out.add(text.substring(start));if(out.isEmpty()||out.size()>10||!String.join("",out).equals(text))throw new ApiException(ErrorCode.INVALID_SCHEMA);return out;
    }
    @Transactional public Object status(ActorPrincipal actor,UUID child,UUID id){access(actor,child);if(!release(id).get("child_id").equals(child))throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);return view(id);}
    private JsonNode view(UUID id) {
        var r=release(id);var out=json.createObjectNode().put("releaseId",id.toString()).put("jobId",r.get("job_id").toString()).put("revision",((Number)r.get("revision")).longValue()).put("state",(String)r.get("state")).put("approvedDraftHash",(String)r.get("draft_hash"));
        var m=out.putObject("mediaProgress");m.put("totalSentences",db.queryForObject("select count(*) from learning.prototype_media where release_id=?",Integer.class,id));m.put("readySentences",db.queryForObject("select count(*) from learning.prototype_media where release_id=? and state='COMPLETED'",Integer.class,id));
        var failed=m.putArray("failedAssetIds");db.queryForList("select id from learning.prototype_media where release_id=? and state='FAILED' order by sentence_key",UUID.class,id).forEach(v->failed.add(v.toString()));
        if(r.get("plan_id")==null)out.putNull("planId");else out.put("planId",r.get("plan_id").toString());
        if(r.get("error_code")==null)out.putNull("error");else out.putObject("error").put("code",(String)r.get("error_code")).put("stage","MEDIA").put("retryable",r.get("state").equals("MEDIA_FAILED")).put("jobId",r.get("job_id").toString()).put("message","음원 준비 상태를 확인해 주세요. 실행 불명 요청은 재전송하지 않습니다.");return out;
    }
    @Transactional public StoredResponse retry(ActorPrincipal actor,UUID child,UUID id,JsonNode body,String key) {
        contracts.validate("RetryMediaP1",body);
        return idem.execute(actor,"POST","/api/v1/children/"+child+"/day-releases/"+id+"/retry-media",key,body,()->access(actor,child),()->{
            var r=live(id);if(!r.get("child_id").equals(child)||!r.get("state").equals("MEDIA_FAILED")||body.path("expectedVersion").asLong()!=((Number)r.get("revision")).longValue())throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
            for(var asset:body.path("failedAssetIds"))if(db.update("update learning.prototype_media set state='PENDING',error_code=null where id=? and release_id=? and state='FAILED'",UUID.fromString(asset.asText()),id)!=1)throw new ApiException(ErrorCode.STALE_DRAFT);
            db.update("update learning.prototype_release set state='CONFIRMED_MEDIA_PENDING',error_code=null,revision=revision+1,updated_at=? where id=?",ts(),id);return StoredResponse.json(json,202,view(id));
        });
    }
    @Transactional public StoredResponse retryPublication(ActorPrincipal actor,UUID child,UUID id,JsonNode body,String key) {
        if(!body.isObject()||body.size()!=1||!body.path("expectedVersion").canConvertToLong())throw new ApiException(ErrorCode.INVALID_SCHEMA);
        return idem.execute(actor,"POST","/api/v1/children/"+child+"/day-releases/"+id+"/retry-publication",key,body,()->access(actor,child),()->{
            var r=live(id);if(!r.get("child_id").equals(child)||!r.get("state").equals("MEDIA_FAILED")||((Number)r.get("revision")).longValue()!=body.path("expectedVersion").asLong()||db.queryForObject("select count(*) from learning.prototype_media where release_id=? and state<>'COMPLETED'",Long.class,id)!=0)throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
            db.update("update learning.prototype_release set state='CONFIRMED_MEDIA_PENDING',error_code=null,revision=revision+1,updated_at=? where id=?",ts(),id);return StoredResponse.json(json,202,view(id));
        });
    }
    public void enqueue(UUID id){executor.execute(()->process(id));}
    private void process(UUID id) {
        try {
            boolean start=Boolean.TRUE.equals(tx.execute(s->{var r=live(id);if(!r.get("state").equals("CONFIRMED_MEDIA_PENDING"))return false;db.update("update learning.prototype_release set state='MEDIA_RUNNING',revision=revision+1,updated_at=? where id=?",ts(),id);return true;}));if(!start)return;
            var config=worker.mediaConfig();
            tx.executeWithoutResult(s->{var r=live(id);if(!config.path("mode").asText().equals(r.get("mode")))throw new ApiException(ErrorCode.EXECUTION_MODE_MISMATCH);
                if(r.get("media_config")!=null&&!json.readTree(r.get("media_config").toString()).path("configurationHash").equals(config.path("configurationHash")))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
                db.update("update learning.prototype_release set media_config=?::jsonb where id=?",config.toString(),id);});
            while(true) {
                var media=tx.execute(s->{live(id);var rows=db.queryForList("select * from learning.prototype_media where release_id=? and state='PENDING' order by slot,sentence_key limit 1 for update",id);
                    if(rows.isEmpty())return null;var row=rows.getFirst();
                    long attempts=db.queryForObject("select coalesce(sum(attempts),0) from learning.prototype_media where release_id=?",Long.class,id);
                    long characters=db.queryForObject("select coalesce(sum(attempts*length(text)),0) from learning.prototype_media where release_id=?",Long.class,id);
                    if(attempts>=400||characters+((String)row.get("text")).length()>50000)throw new ApiException(ErrorCode.COST_BUDGET_EXCEEDED);
                    db.update("update learning.prototype_media set state='RESERVED',attempts=attempts+1,reserved_at=? where id=?",ts(),row.get("id"));return row;});
                if(media==null)break;
                JsonNode audio;
                try {var request=json.createObjectNode().put("configurationHash",config.path("configurationHash").asText()).put("requestPurpose","STORY").put("text",(String)media.get("text")).put("actorId",(String)media.get("actor_id")).put("emotion",(String)media.get("emotion")).put("intensity",((Number)media.get("intensity")).doubleValue());audio=worker.sentenceMedia(request);}
                catch(AgentWorkerClient.WorkerRejected e){boolean known=Set.of("TYPECAST_AUTH_FAILED","TYPECAST_QUOTA_EXCEEDED","TYPECAST_RATE_LIMITED","TYPECAST_REQUEST_REJECTED","TYPECAST_INVALID_AUDIO","TYPECAST_NOT_CONFIGURED","TYPECAST_CONFIGURATION_INVALID","TYPECAST_VOICE_CAPABILITY_REQUIRED").contains(e.code());markMedia(id,(UUID)media.get("id"),known?"FAILED":"OUTCOME_UNKNOWN",e.code());throw e;}
                catch(RuntimeException unknown){markMedia(id,(UUID)media.get("id"),"OUTCOME_UNKNOWN","MEDIA_OUTCOME_UNKNOWN");throw unknown;}
                tx.executeWithoutResult(s->{live(id);byte[] bytes=Base64.getDecoder().decode(audio.path("audioBase64").asText());String hash=CoreDependencyValidator.sha(bytes);
                    if(!hash.equals(audio.path("audioHash").asText())||bytes.length!=audio.path("byteLength").asInt()||!config.path("configurationHash").equals(audio.path("configurationHash")))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
                    PrototypeBinding.validateWave(bytes,audio.path("durationMs").asInt());UUID asset=(UUID)media.get("id");content.artifact(new CoreDependencyValidator.Artifact(asset,"MEDIA","p1-speech-"+asset,bytes,hash));
                    db.update("update learning.prototype_media set state='COMPLETED',artifact_id=?,audio_hash=?,byte_length=?,duration_ms=?,acting=?::jsonb,completed_at=? where id=? and state='RESERVED'",asset,hash,bytes.length,audio.path("durationMs").asInt(),audio.path("typecastActing").toString(),ts(),asset);
                });
            }
            tx.executeWithoutResult(s->publish(id));
        }catch(RuntimeException error) {
            // Only class/source location, never exception messages/SQL/provider bodies/child text.
            var at=error.getStackTrace().length==0?null:error.getStackTrace()[0];
            org.slf4j.LoggerFactory.getLogger(PrototypeReleaseService.class).warn("PROTOTYPE_RELEASE_FAILURE release={} type={} at={} line={}",id,error.getClass().getSimpleName(),at==null?"unknown":at.getClassName()+"#"+at.getMethodName(),at==null?0:at.getLineNumber());
            tx.executeWithoutResult(s->{var unresolved=db.queryForList("select id from learning.prototype_media where release_id=? and state in ('RESERVED','OUTCOME_UNKNOWN')",UUID.class,id);
                if(!unresolved.isEmpty())db.update("update learning.prototype_media set state='OUTCOME_UNKNOWN',error_code='MEDIA_OUTCOME_UNKNOWN' where release_id=? and state='RESERVED'",id);
                else db.update("update learning.prototype_media set state='FAILED',error_code='MEDIA_PREPARATION_FAILED' where release_id=? and state='PENDING'",id);
                db.update("update learning.prototype_release set state=?,revision=revision+1,error_code=?,updated_at=? where id=? and state='MEDIA_RUNNING'",unresolved.isEmpty()?"MEDIA_FAILED":"OUTCOME_UNKNOWN",error instanceof ApiException e?e.code().name():"MEDIA_PREPARATION_FAILED",ts(),id);
            });
        }
    }
    private void markMedia(UUID release,UUID id,String state,String code){tx.executeWithoutResult(s->{current.lockDomain();db.update("update learning.prototype_media set state=?,error_code=? where id=? and release_id=? and state='RESERVED'",state,code.matches("[A-Z_]{1,80}")?code:"MEDIA_FAILED",id,release);});}
    private void publish(UUID id) {
        var r=live(id);if(!r.get("state").equals("MEDIA_RUNNING")||db.queryForObject("select count(*) from learning.prototype_media where release_id=? and state<>'COMPLETED'",Integer.class,id)>0)throw new ApiException(ErrorCode.MATERIAL_NOT_READY);
        var artifact=artifact((UUID)r.get("job_id"));var publications=new ArrayList<UUID>();
        var drafts=artifact.path("coreDrafts");
        var cues=binding.cuesForConfiguration(artifact.path("result").path("provenance").path("configurationRef").asText());
        for(var draft:drafts) {
            int slot=draft.path("slot").asInt();var lines=db.queryForList("select * from learning.prototype_media where release_id=? and slot=? order by scene_id,sentence_index",id,slot);
            var manifest=json.createObjectNode().put("schemaVersion","EF_FOUNDATION_BINDING_P1").put("approvedDraftHash",(String)r.get("draft_hash")).put("approvalId",id.toString())
                .put("sourceContentHash",binding.sourceHash(draft.path("payload"))).put("cardPolicyHash",PrototypeJson.textHash("p1-cards-6-min4-max8-selected4-no-extra-approval-v1"))
                .put("cueCatalogRef",cues.path("catalogId").asText()).put("cueCatalogHash",PrototypeJson.digest(cues)).put("installedAppAssetVersion",cues.path("installedAppAssetVersion").asText());
            manifest.set("cardVersions",artifact.path("result").path("provenance").path("cardVersions"));var assets=manifest.putArray("assets");
            for(var line:lines)assets.addObject().put("assetId",line.get("artifact_id").toString()).put("sourceId","p1-speech-"+line.get("artifact_id")).put("audioHash",(String)line.get("audio_hash")).put("byteLength",((Number)line.get("byte_length")).intValue()).put("durationMs",((Number)line.get("duration_ms")).intValue()).put("encoding","WAV_PCM16_MONO_24000");
            manifest.put("manifestHash",PrototypeJson.digest(manifest));
            var presentation=json.createObjectNode().put("schemaVersion","EF_FOUNDATION_PRESENTATION_P1").put("scenarioVersionId",draft.path("scenarioVersionId").asText());var nodes=presentation.putArray("nodes");
            for(var source:draft.path("payload").path("sceneGraph").path("scenes")) {
                var node=nodes.addObject().put("sceneId",source.path("sceneId").asText());node.putArray("stageDirections");var sentences=node.putArray("sentences");
                for(var line:lines)if(line.get("scene_id").equals(source.path("sceneId").asText())) {
                    var sentence=sentences.addObject().put("sentenceId",(String)line.get("sentence_id")).put("text",(String)line.get("text")).put("renderedTextHash",(String)line.get("text_hash")).put("voiceAssetId",line.get("artifact_id").toString()).put("audioHash",(String)line.get("audio_hash")).put("actorId",(String)line.get("actor_id")).put("semanticEmotion",(String)line.get("emotion")).put("emotionIntensity",((Number)line.get("intensity")).doubleValue()).put("faceCue",(String)line.get("face_cue")).put("animationCue",(String)line.get("animation_cue"));sentence.set("typecastActing",json.readTree(line.get("acting").toString()));
                }
            }
            publications.add(content.publishPrototype(actor(r),id,slot,UUID.fromString(draft.path("materialId").asText()),manifest,presentation,lines.stream().map(l->(UUID)l.get("artifact_id")).toList()));
        }
        int day=db.queryForObject("select program_day from learning.prototype_day where release_id=?",Integer.class,id);
        UUID plan=content.plan(actor(r),(UUID)r.get("child_id"),day,"Asia/Seoul",publications);
        db.update("update learning.prototype_day set plan_id=? where release_id=?",plan,id);db.update("update learning.prototype_release set state='READY',plan_id=?,revision=revision+1,updated_at=? where id=?",plan,ts(),id);
    }
    private Timestamp ts(){return Timestamp.from(clock.instant());}
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverKnownPending() {
        if(!enabled)return;
        // 확정 뒤 서버가 꺼져도 승인한 원고와 성공한 음원을 재사용합니다.
        // 외부 호출을 예약한 채 응답을 저장하지 못한 문장은 실행 불명으로 보존합니다.
        // 아직 전송하지 않은 작업만 다시 큐에 넣으며 동의/권한은 process의 live()에서 재확인합니다.
        var pending=tx.execute(s->{
            current.lockDomain();
            db.update("""
                update learning.prototype_media m set state='OUTCOME_UNKNOWN',error_code='SERVER_RESTART'
                from learning.prototype_release r where r.id=m.release_id
                and r.state in ('CONFIRMED_MEDIA_PENDING','MEDIA_RUNNING') and m.state='RESERVED'
                """);
            db.update("""
                update learning.prototype_release r set state='OUTCOME_UNKNOWN',error_code='SERVER_RESTART',
                revision=revision+1,updated_at=? where r.state in ('CONFIRMED_MEDIA_PENDING','MEDIA_RUNNING')
                and exists(select 1 from learning.prototype_media m where m.release_id=r.id and m.state='OUTCOME_UNKNOWN')
                """,ts());
            db.update("update learning.prototype_release set state='CONFIRMED_MEDIA_PENDING',revision=revision+1,updated_at=? where state='MEDIA_RUNNING'",ts());
            return db.queryForList("select id from learning.prototype_release where state='CONFIRMED_MEDIA_PENDING' order by approved_at,id",UUID.class);
        });
        if(pending!=null)pending.forEach(this::enqueue);
    }
    @Override public void close(){executor.shutdown();}
}
