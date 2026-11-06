package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.agent.*;
import com.planb.emotionfriend.api.children.ChildContracts.ConsentScope;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.*;
import java.time.*;
import java.sql.Timestamp;
import java.math.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 카드/확장에는 별도 보호자 승인 단계가 없다. 짧은 형식 검사와 현재 원문/경로 결속 뒤 아동이 확정한다.
 * 외부 호출 동안 DB transaction을 잡지 않으며, 원문/후보는 암호화하여 15분만 보관한다. */
@Service
public class PrototypeExpressionService {
    private final JdbcTemplate db;private final JsonMapper json;private final LearningCurrent current;private final PrototypeContracts contracts;
    private final AgentWorkerClient worker;private final ReplayCipher cipher;private final RequestFingerprint fingerprint;private final IdempotencyService idem;
    private final Clock clock;private final TransactionTemplate tx;private final boolean live;private final BigDecimal inputPrice,outputPrice;
    private final long maxCalls,maxInput,maxOutput,maxCost;
    public PrototypeExpressionService(JdbcTemplate db,JsonMapper json,LearningCurrent current,PrototypeContracts contracts,AgentWorkerClient worker,
        ReplayCipher cipher,RequestFingerprint fingerprint,IdempotencyService idem,Clock clock,PlatformTransactionManager manager,
        @Value("${emotion-friend.prototype.live-enabled:false}") boolean live,
        @Value("${emotion-friend.prototype.input-usd-per-million:0}") BigDecimal inputPrice,@Value("${emotion-friend.prototype.output-usd-per-million:0}") BigDecimal outputPrice,
        @Value("${emotion-friend.prototype.max-calls:85}") long maxCalls,@Value("${emotion-friend.prototype.max-input-tokens:250000}") long maxInput,
        @Value("${emotion-friend.prototype.max-output-tokens:32000}") long maxOutput,@Value("${emotion-friend.prototype.max-cost-microusd:2000000}") long maxCost) {
        this.db=db;this.json=json;this.current=current;this.contracts=contracts;this.worker=worker;this.cipher=cipher;this.fingerprint=fingerprint;this.idem=idem;this.clock=clock;tx=new TransactionTemplate(manager);
        this.live=live;this.inputPrice=inputPrice;this.outputPrice=outputPrice;this.maxCalls=maxCalls;this.maxInput=maxInput;this.maxOutput=maxOutput;this.maxCost=maxCost;
    }
    private LearningCurrent.Progress access(ActorPrincipal actor,UUID session,int slot,boolean cards) {
        var purposes=cards?Set.of(ConsentScope.CORE_PROFILE_LEARNING,ConsentScope.CARD_EXTERNAL_PROCESSING):Set.of(ConsentScope.CORE_PROFILE_LEARNING);
        var p=current.read(new LearningCurrent.Query(actor,UUID.fromString(actor.childId()),null,new LearningCurrent.SessionSlot(session,slot),purposes)).progress();
        if(p.terminalSceneId()==null||p.emotionId()==null)throw new ApiException(ErrorCode.STALE_CHECKPOINT);
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from learning.prototype_package p join learning.core_evidence e on e.validation_id=p.validation_id where e.id=?)",Boolean.class,p.publicationId())))throw new ApiException(ErrorCode.UNSUPPORTED_SCHEMA_VERSION);return p;
    }
    public Object context(ActorPrincipal actor,UUID session,int slot){return tx.execute(s->{access(actor,session,slot,false);return Map.of("contextHash",PrototypeExpressionContext.hash(db,json,session,slot));});}
    public Object offer(ActorPrincipal actor,UUID session,int slot,UUID id){return tx.execute(s->{var p=access(actor,session,slot,true);return saved(id,session,slot,"OFFER",p.draftVersion(),PrototypeExpressionContext.hash(db,json,session,slot),null);});}
    private void bind(LearningCurrent.Progress p,UUID session,int slot,JsonNode b) {
        if(p.draftVersion()!=b.path("draftVersion").asLong()||!PrototypeExpressionContext.hash(db,json,session,slot).equals(b.path("contextHash").asText()))throw new ApiException(ErrorCode.STALE_DRAFT);
    }
    private JsonNode saved(UUID id,UUID session,int slot,String kind,long version,String context,String original) {
        var rows=db.queryForList("select * from learning.prototype_expression_candidate where id=? and session_id=? and slot=? and kind=?",id,session,slot,kind);
        if(rows.isEmpty())throw new ApiException(ErrorCode.STALE_DRAFT);var r=rows.getFirst();
        if(((Number)r.get("draft_version")).longValue()!=version||!r.get("context_hash").equals(context)||original!=null&&!r.get("original_hash").equals(original)||!((Timestamp)r.get("expires_at")).toInstant().isAfter(clock.instant()))throw new ApiException(ErrorCode.STALE_DRAFT);
        return json.readTree(cipher.decrypt((byte[])r.get("encrypted_body"),"prototype-expression:"+id));
    }
    private JsonNode selected(JsonNode offer,JsonNode ids) {
        var cards=json.createArrayNode();for(var id:ids)cards.add(offer.path("cards").valueStream().filter(c->c.path("cardId").equals(id)).findFirst().orElseThrow(()->new ApiException(ErrorCode.STALE_DRAFT)));return cards;
    }
    private ObjectNode payload(LearningCurrent.Progress p,UUID session,int slot,JsonNode b) {
        var out=(ObjectNode)b.deepCopy();var context=out.putObject("context");context.put("sessionId",session.toString()).put("slot",slot).put("contextHash",b.path("contextHash").asText());
        var source=db.queryForMap("select s.input_json::text,s.input_metadata::text from learning.prototype_day d join learning.generation_job j on j.id=d.job_id join learning.agent_source s on s.id=j.source_ref where d.plan_id=?",p.planId());
        context.set("childProfile",json.readTree((String)source.get("input_json")).path("childProfile"));context.put("configurationRef",json.readTree((String)source.get("input_metadata")).path("configurationRef").asText());
        var body=json.readTree(db.queryForObject("select payload from learning.core_manuscript where scenario_version_id=?",String.class,p.scenarioVersionId()));
        var scenes=new HashMap<String,JsonNode>();body.path("sceneGraph").path("scenes").forEach(n->scenes.put(n.path("sceneId").asText(),n));
        String id=body.path("sceneGraph").path("startSceneId").asText();int choice=0;var actual=context.putArray("actualDialogue");
        for(int n=0;n<=scenes.size();n++) {var node=scenes.get(id);if(node==null)throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);
            actual.addObject().put("speakerId",node.path("actorId").asText()).put("text",node.path("dialogue").asText());if(node.path("end").asBoolean())break;
            if(node.has("nextSceneId")){id=node.path("nextSceneId").asText();continue;}
            if(choice>=p.choiceIds().size())throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);String cid=p.choiceIds().get(choice++);
            var c=node.path("choices").valueStream().filter(v->v.path("choiceId").asText().equals(cid)).findFirst().orElseThrow(()->new ApiException(ErrorCode.INVALID_BRANCH_PATH));
            actual.addObject().put("speakerId","child").put("text",c.path("label").asText());id=c.path("nextSceneId").asText();
        }return out;
    }
    private record Reservation(UUID id,JsonNode cached,ObjectNode payload,boolean paid,String model) {}
    public JsonNode generate(ActorPrincipal actor,UUID session,int slot,JsonNode body,String key,boolean options) {
        contracts.validate(options?"ExpressionOptionsCommandP1":"ExpandExpressionCommandP1",body);
        if(key==null||key.length()<8||key.length()>128)throw new ApiException(ErrorCode.INVALID_SCHEMA);
        String kind=options?"OPTIONS":"EXPANSION",digest=fingerprint.request(body);
        var reservation=tx.execute(s->{var p=access(actor,session,slot,true);bind(p,session,slot,body);
            var prior=db.queryForList("select * from learning.prototype_expression_call where session_id=? and slot=? and kind=? and key_digest=?",session,slot,kind,fingerprint.key(key));
            if(!prior.isEmpty()) {var r=prior.getFirst();if(!digest.equals(r.get("request_digest")))throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
                if(!r.get("state").equals("COMPLETED")||r.get("response_ref")==null)throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
                return new Reservation((UUID)r.get("id"),saved((UUID)r.get("response_ref"),session,slot,options?"OFFER":"CANDIDATE",p.draftVersion(),body.path("contextHash").asText(),PrototypeJson.textHash(body.path("originalText").asText())),null,false,"");}
            // A new explicit recommendation can follow an expired offer. Unknown reservations remain blocked.
            db.update("update learning.prototype_expression_call c set state='FAILED' from learning.prototype_expression_candidate v where c.response_ref=v.id and c.state='COMPLETED' and v.expires_at<=?",ts());
            var equivalent=db.queryForList("select * from learning.prototype_expression_call where session_id=? and slot=? and kind=? and business_digest=? and state in ('RESERVED','COMPLETED','OUTCOME_UNKNOWN')",session,slot,kind,digest);
            if(!equivalent.isEmpty()){
                var r=equivalent.getFirst();if(!r.get("state").equals("COMPLETED")||r.get("response_ref")==null)throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
                return new Reservation((UUID)r.get("id"),saved((UUID)r.get("response_ref"),session,slot,options?"OFFER":"CANDIDATE",p.draftVersion(),body.path("contextHash").asText(),PrototypeJson.textHash(body.path("originalText").asText())),null,false,"");
            }
            if(db.queryForObject("select count(*) from learning.prototype_expression_call where session_id=? and slot=?",Long.class,session,slot)>=12)throw new ApiException(ErrorCode.COST_BUDGET_EXCEEDED);
            var payload=payload(p,session,slot,body);String originalHash=PrototypeJson.textHash(body.path("originalText").asText());
            if(options) {
                JsonNode retained=json.createArrayNode();if(!body.path("previousOfferId").isNull())retained=selected(saved(UUID.fromString(body.path("previousOfferId").asText()),session,slot,"OFFER",p.draftVersion(),body.path("contextHash").asText(),originalHash),body.path("selectedCardIds"));
                else if(!body.path("selectedCardIds").isEmpty())throw new ApiException(ErrorCode.STALE_DRAFT);
                payload.set("retainedCards",retained);payload.put("cardsNeeded",6-retained.size());
            }else payload.set("selectedCards",selected(saved(UUID.fromString(body.path("offerId").asText()),session,slot,"OFFER",p.draftVersion(),body.path("contextHash").asText(),originalHash),body.path("selectedCardIds")));
            var r=db.queryForMap("select ar.mode,d.local_date,s.input_metadata->>'modelRef' as model from learning.prototype_day d join learning.agent_run ar on ar.job_id=d.job_id join learning.generation_job j on j.id=d.job_id join learning.agent_source s on s.id=j.source_ref where d.plan_id=?",p.planId());boolean paid=r.get("mode").equals("LIVE");
            if(paid&&(!live||inputPrice.signum()<=0||outputPrice.signum()<=0))throw new ApiException(ErrorCode.EXECUTION_MODE_MISMATCH);
            long in=paid?json.writeValueAsBytes(payload).length+4096:0,out=paid?1000:0,cost=cost(in,out);
            if(paid){var t=db.queryForMap("select count(*) calls,coalesce(sum(i),0) i,coalesce(sum(o),0) o,coalesce(sum(c),0) c from (select coalesce(input_used,input_reserved) i,coalesce(output_used,output_reserved) o,coalesce(cost_used,cost_reserved) c from learning.agent_call where child_id=? and local_date=? and mode='LIVE' union all select coalesce(input_used,input_reserved),coalesce(output_used,output_reserved),coalesce(cost_used,cost_reserved) from learning.prototype_expression_call where child_id=? and local_date=? and mode='LIVE') t",UUID.fromString(actor.childId()),r.get("local_date"),UUID.fromString(actor.childId()),r.get("local_date"));
                if(number(t,"calls")+1>maxCalls||number(t,"i")+in>maxInput||number(t,"o")+out>maxOutput||number(t,"c")+cost>maxCost)throw new ApiException(ErrorCode.COST_BUDGET_EXCEEDED);}
            UUID id=UUID.randomUUID();db.update("insert into learning.prototype_expression_call(id,child_id,session_id,slot,local_date,kind,mode,key_digest,request_digest,business_digest,state,input_reserved,output_reserved,cost_reserved,created_at) values(?,?,?,?,?,?,?,?,?,?,'RESERVED',?,?,?,?)",id,UUID.fromString(actor.childId()),session,slot,r.get("local_date"),kind,r.get("mode"),fingerprint.key(key),digest,digest,in,out,cost,ts());
            return new Reservation(id,null,payload,paid,(String)r.get("model"));
        });
        if(reservation.cached()!=null)return reservation.cached();
        JsonNode response;
        try{response=worker.runtimeExpression(json.createObjectNode().put("operation",kind).set("payload",reservation.payload()));}
        catch(RuntimeException e){boolean known=e instanceof AgentWorkerClient.WorkerRejected rejected&&Set.of("PROVIDER_REJECTED","CONFIG_UNAVAILABLE","INPUT_INVALID").contains(rejected.code());
            tx.executeWithoutResult(s->db.update("update learning.prototype_expression_call set state=?,input_used=?,output_used=?,cost_used=?,settled_at=? where id=? and state='RESERVED'",known?"FAILED":"OUTCOME_UNKNOWN",known?0:null,known?0:null,known?0:null,ts(),reservation.id()));throw new ApiException(ErrorCode.CARD_SERVICE_UNAVAILABLE);}
        // Record real usage before output admission or current-state checks, even if the child moved on.
        boolean metadataValid=tx.execute(s->{if(!response.path("model").asText().equals(reservation.model())||response.path("mode").asText().equals("LIVE")!=reservation.paid()||!response.path("inputTokens").canConvertToLong()||response.path("inputTokens").asLong()<0||!response.path("outputTokens").canConvertToLong()||response.path("outputTokens").asLong()<0){db.update("update learning.prototype_expression_call set state='OUTCOME_UNKNOWN',settled_at=? where id=?",ts(),reservation.id());return false;}
            long in=reservation.paid()?response.path("inputTokens").asLong():0,out=reservation.paid()?response.path("outputTokens").asLong():0;
            db.update("update learning.prototype_expression_call set state='FAILED',input_used=?,output_used=?,cost_used=?,provider_request_id=?,settled_at=? where id=? and state='RESERVED'",in,out,cost(in,out),response.path("providerRequestId").asText(null),ts(),reservation.id());return true;});
        if(!metadataValid)throw new ApiException(ErrorCode.EXECUTION_MODE_MISMATCH);
        return tx.execute(s->{var p=access(actor,session,slot,true);bind(p,session,slot,body);var candidate=response.path("candidate");if(!candidate.isObject())throw new ApiException(ErrorCode.CARD_SERVICE_UNAVAILABLE);
            UUID id=UUID.randomUUID();var result=json.createObjectNode().put(options?"offerId":"candidateId",id.toString()).put("draftVersion",p.draftVersion()).put("contextHash",body.path("contextHash").asText()).put("originalHash",PrototypeJson.textHash(body.path("originalText").asText()));
            if(options){var cards=result.putArray("cards");reservation.payload().path("retainedCards").forEach(cards::add);var seen=new HashSet<String>();cards.forEach(c->seen.add(c.path("text").asText()));
                if(!candidate.path("cards").isArray()||candidate.path("cards").size()!=6-cards.size())throw new ApiException(ErrorCode.CARD_SERVICE_UNAVAILABLE);
                for(var c:candidate.path("cards")){String text=c.path("text").asText().strip();if(text.codePointCount(0,text.length())>16||text.split("\\s+").length>2||text.matches(".*[.!?。！？\\n\\r].*")||!seen.add(text)||!Set.of("FEELING","REASON","WISH","THOUGHT","INTENSITY","BOUNDARY","UNCERTAINTY").contains(c.path("intent").asText()))throw new ApiException(ErrorCode.CARD_SERVICE_UNAVAILABLE);cards.addObject().put("cardId",UUID.randomUUID().toString()).put("text",text).put("intent",c.path("intent").asText());}
                result.set("retainedSelectedCardIds",body.path("selectedCardIds"));
            }else {result.put("offerId",body.path("offerId").asText());result.set("selectedCardIds",body.path("selectedCardIds"));result.put("composedText",candidate.path("composedText").asText()).put("reactionText",candidate.path("reactionText").asText());result.put("candidateHash",PrototypeJson.digest(result));}
            var expiry=clock.instant().plusSeconds(900);result.put("expiresAt",expiry.toString());contracts.validate(options?"CardOfferP1":"ExpansionCandidateP1",result);
            db.update("insert into learning.prototype_expression_candidate values(?,?,?,?,?,?,?,?,?,?,?,?)",id,reservation.id(),session,slot,p.attemptId(),options?"OFFER":"CANDIDATE",p.draftVersion(),body.path("contextHash").asText(),result.path("originalHash").asText(),cipher.encrypt(json.writeValueAsBytes(result),"prototype-expression:"+id),Timestamp.from(expiry),ts());
            db.update("update learning.prototype_expression_call set state='COMPLETED',response_ref=? where id=?",id,reservation.id());return result;});
    }
    // 작업 API도 기존 권한·동의·원문/실제경로 검사를 함께 사용합니다. 별도 승인은 아닙니다.
    public ObjectNode jobScope(ActorPrincipal actor,UUID session,int slot){return tx.execute(s->{var p=access(actor,session,slot,true);
        return json.createObjectNode().put("attemptId",p.attemptId().toString()).put("draftVersion",p.draftVersion()).put("contextHash",PrototypeExpressionContext.hash(db,json,session,slot));});}
    public void validateJob(ActorPrincipal actor,UUID session,int slot,JsonNode body,boolean options){
        contracts.validate(options?"ExpressionOptionsCommandP1":"ExpandExpressionCommandP1",body);
        tx.executeWithoutResult(s->{var p=access(actor,session,slot,true);bind(p,session,slot,body);});
    }
    public JsonNode jobResult(ActorPrincipal actor,UUID session,int slot,UUID result,String kind,long version,String context,String original){return tx.execute(s->{var p=access(actor,session,slot,true);
        if(p.draftVersion()!=version||!PrototypeExpressionContext.hash(db,json,session,slot).equals(context))throw new ApiException(ErrorCode.STALE_DRAFT);
        return saved(result,session,slot,kind.equals("OPTIONS")?"OFFER":"CANDIDATE",version,context,original);});}
    public StoredResponse confirm(ActorPrincipal actor,UUID session,int slot,JsonNode body,String key) {
        contracts.validate("ConfirmExpressionP1",body);
        return tx.execute(s->idem.execute(actor,"POST","/api/v1/sessions/"+session+"/slots/"+slot+"/confirmed-expressions",key,body,()->access(actor,session,slot,body.path("expressionChoice").asText().equals("AI_COMPOSED")),()->{
            var p=access(actor,session,slot,body.path("expressionChoice").asText().equals("AI_COMPOSED"));long version=body.path("draftVersion").asLong();if(p.draftVersion()!=version)throw new ApiException(ErrorCode.STALE_DRAFT);
            String context=PrototypeExpressionContext.hash(db,json,session,slot),choice=body.path("expressionChoice").asText(),reaction;UUID candidateId=null;
            if(choice.equals("AI_COMPOSED")){candidateId=UUID.fromString(body.path("candidateId").asText());reaction=saved(candidateId,session,slot,"CANDIDATE",version,context,null).path("reactionText").asText();}
            else {var manuscript=json.readTree(db.queryForObject("select payload from learning.core_manuscript where scenario_version_id=?",String.class,p.scenarioVersionId()));
                reaction=manuscript.path("characterReplyPolicy").path("rules").valueStream().filter(r->r.path("ruleId").asText().equals("original-fallback")&&r.path("whenFunction").asText().equals("UNKNOWN")).findFirst().orElseThrow(()->new ApiException(ErrorCode.CONTENT_NOT_APPROVED)).path("text").asText();}
            UUID proof=UUID.randomUUID();var expiry=clock.instant().plusSeconds(900);db.update("insert into learning.prototype_expression_proof values(?,?,?,?,?,?,?,?,?,?,?)",proof,session,slot,actor.actorId(),version,choice,candidateId,context,reaction,Timestamp.from(expiry),ts());
            return StoredResponse.json(json,201,new LearningContracts.Expression(proof,expiry,version));}));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000) public void discardExpiredPrivateBodies(){db.update("delete from learning.prototype_expression_candidate where expires_at<? and id not in (select candidate_id from learning.prototype_expression_proof where candidate_id is not null) and id not in (select response_ref from learning.prototype_expression_call where response_ref is not null)",ts());
        // Overwrite expired ciphertext even while FK metadata remains. No child text enters a long-term record.
        db.update("update learning.prototype_expression_candidate set encrypted_body=decode('','hex') where expires_at<? and octet_length(encrypted_body)>0",ts());}
    private long cost(long in,long out){return inputPrice.multiply(BigDecimal.valueOf(in)).add(outputPrice.multiply(BigDecimal.valueOf(out))).setScale(0,RoundingMode.CEILING).longValueExact();}
    private long number(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}private Timestamp ts(){return Timestamp.from(clock.instant());}
}
