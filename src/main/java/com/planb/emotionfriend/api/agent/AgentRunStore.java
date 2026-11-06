package com.planb.emotionfriend.api.agent;

import com.planb.emotionfriend.api.children.ChildContracts.ConsentScope;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.learning.*;
import java.util.*;
import java.time.Clock;
import java.sql.Timestamp;
import java.math.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Durable provider reservations belong to Core. A worker restart cannot erase an unresolved charge. */
@Service
@Transactional
public class AgentRunStore {
    private final JdbcTemplate db;private final JsonMapper json;private final LearningCurrent current;
    private final ScenarioInputService inputs;private final Clock clock;private final boolean live;
    private final long maxCalls,maxInput,maxOutput,maxCost;private final BigDecimal inputPrice,outputPrice;
    public AgentRunStore(JdbcTemplate db,JsonMapper json,LearningCurrent current,ScenarioInputService inputs,Clock clock,
        @Value("${emotion-friend.prototype.live-enabled:false}") boolean live,
        @Value("${emotion-friend.prototype.max-calls:85}") long maxCalls,
        @Value("${emotion-friend.prototype.max-input-tokens:250000}") long maxInput,
        @Value("${emotion-friend.prototype.max-output-tokens:32000}") long maxOutput,
        @Value("${emotion-friend.prototype.max-cost-microusd:2000000}") long maxCost,
        @Value("${emotion-friend.prototype.input-usd-per-million:0}") BigDecimal inputPrice,
        @Value("${emotion-friend.prototype.output-usd-per-million:0}") BigDecimal outputPrice) {
        this.db=db;this.json=json;this.current=current;this.inputs=inputs;this.clock=clock;this.live=live;
        this.maxCalls=maxCalls;this.maxInput=maxInput;this.maxOutput=maxOutput;this.maxCost=maxCost;this.inputPrice=inputPrice;this.outputPrice=outputPrice;
        if(maxCalls<1||maxCalls>100||maxInput<1||maxOutput<1||maxCost<1||inputPrice.signum()<0||outputPrice.signum()<0)throw new IllegalArgumentException("Invalid agent budget configuration");
        if(live&&(inputPrice.signum()==0||outputPrice.signum()==0))throw new IllegalArgumentException("LIVE requires explicitly verified nonzero model prices");
    }
    private JsonNode source(UUID id,boolean requireRunning) {
        current.lockDomain();
        var rows=db.queryForList("""
            select s.actor_json::text,s.snapshot_json::text,s.input_json::text,s.frozen_profile,j.state,j.deadline
            from learning.generation_job j join learning.agent_source s on s.id=j.source_ref where j.id=?
            """,id);
        if(rows.isEmpty())throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);var row=rows.getFirst();
        var pin=json.readValue((String)row.get("snapshot_json"),LearningCurrent.Snapshot.class);
        var a=json.readTree((String)row.get("actor_json"));var actor=new ActorPrincipal(a.path("actorId").asText(),ActorPrincipal.Kind.ADULT,null,null,null,a.path("sessionId").asText(),false);
        var now=current.read(new LearningCurrent.Query(actor,pin.childId(),ChildAccessChecker.Capability.MANAGE,null,Set.of(ConsentScope.CORE_PROFILE_LEARNING)));
        var input=(tools.jackson.databind.node.ObjectNode)json.readTree((String)row.get("input_json"));input.put("jobId",id.toString());
        boolean frozen=Boolean.TRUE.equals(row.get("frozen_profile"));
        if(!(frozen?AgentJobService.sameAuthority(now,pin):pin.equals(now))||!frozen&&input.path("sourceSnapshot").path("inputRevision").asLong()!=inputs.inputRevision(pin.childId(),pin.profileVersion()))throw new ApiException(ErrorCode.STALE_DRAFT);
        if(requireRunning&&(!"RUNNING".equals(row.get("state"))||!clock.instant().isBefore(((Timestamp)row.get("deadline")).toInstant())))throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
        return input;
    }
    public Object start(UUID id,JsonNode input,String mode) {
        var expected=source(id,true);
        if(!expected.equals(input)||!Set.of("MOCK","LIVE").contains(mode))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
        if(mode.equals("LIVE")&&!live)throw new ApiException(ErrorCode.EXECUTION_MODE_MISMATCH);
        String frozenMode=db.queryForObject("select coalesce(s.input_metadata->>'generationMode',(select r.mode from learning.agent_run r where r.job_id=j.id),'MOCK') from learning.generation_job j join learning.agent_source s on s.id=j.source_ref where j.id=?",String.class,id);
        if(!mode.equals(frozenMode))throw new ApiException(ErrorCode.EXECUTION_MODE_MISMATCH);
        String hash=CoreDependencyValidator.sha(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.update("insert into learning.agent_run(job_id,input_hash,mode,state,created_at,updated_at) values(?,?,?,'RUNNING',?,?) on conflict(job_id) do nothing",id,hash,mode,ts(),ts());
        var r=db.queryForMap("select input_hash,mode from learning.agent_run where job_id=?",id);
        if(!hash.equals(r.get("input_hash"))||!mode.equals(r.get("mode")))throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
        return status(id);
    }
    public Object status(UUID id) {
        source(id,false);var rows=db.queryForList("select state,result_json::text,error_code from learning.agent_run where job_id=?",id);
        if(rows.isEmpty())return Map.of("state","NOT_STARTED");var row=rows.getFirst();var out=json.createObjectNode();out.put("state",(String)row.get("state"));
        if(row.get("result_json")!=null)out.set("result",json.readTree((String)row.get("result_json")));
        if(row.get("error_code")!=null)out.put("errorCode",(String)row.get("error_code"));return out;
    }
    public Object reserve(UUID id,String key,JsonNode request) {
        if(!key.matches("[a-zA-Z0-9:_-]{1,100}")||request.toString().length()>200000)throw new ApiException(ErrorCode.INVALID_SCHEMA);
        var input=source(id,true);var run=db.queryForMap("select mode,state from learning.agent_run where job_id=? for update",id);
        String expectedModel=db.queryForObject("select s.input_metadata->>'modelRef' from learning.generation_job j join learning.agent_source s on s.id=j.source_ref where j.id=?",String.class,id);
        if(expectedModel==null||!expectedModel.equals(request.path("model").asText()))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
        if(!"RUNNING".equals(run.get("state")))throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
        String hash=CoreDependencyValidator.sha(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var previous=db.queryForList("select request_hash,state,response_json::text,provider_request_id from learning.agent_call where job_id=? and call_key=?",id,key);
        if(!previous.isEmpty()) {
            var prior=previous.getFirst();if(!hash.equals(prior.get("request_hash")))throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
            var out=json.createObjectNode();out.put("state",(String)prior.get("state"));out.put("created",false);
            if(prior.get("response_json")!=null)out.set("response",json.readTree((String)prior.get("response_json")));
            if(prior.get("provider_request_id")!=null)out.put("providerRequestId",(String)prior.get("provider_request_id"));return out;
        }
        boolean paid="LIVE".equals(run.get("mode"));
        long in=paid?request.path("messages").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+512:0;
        long out=paid?request.path("max_completion_tokens").asLong():0;
        if(paid&&(out<1||out>8000))throw new ApiException(ErrorCode.INVALID_SCHEMA);
        long cost=paid?cost(in,out):0;
        UUID child=UUID.fromString(input.path("childProfile").path("childId").asText());var date=java.sql.Date.valueOf(input.path("program").path("localDate").asText());
        // Current domain lock serializes child/day reservations across jobs and workers.
        var totals=db.queryForMap("""
            select count(*) as calls,coalesce(sum(coalesce(input_used,input_reserved)),0) as input,
              coalesce(sum(coalesce(output_used,output_reserved)),0) as output,
              coalesce(sum(coalesce(cost_used,cost_reserved)),0) as cost
            from (select input_used,input_reserved,output_used,output_reserved,cost_used,cost_reserved from learning.agent_call where child_id=? and local_date=? and mode='LIVE'
             union all select input_used,input_reserved,output_used,output_reserved,cost_used,cost_reserved from learning.prototype_expression_call where child_id=? and local_date=? and mode='LIVE') combined
            """,child,date,child,date);
        if(paid&&(((Number)totals.get("calls")).longValue()+1>maxCalls||number(totals,"input")+in>maxInput
            ||number(totals,"output")+out>maxOutput||number(totals,"cost")+cost>maxCost))throw new ApiException(ErrorCode.COST_BUDGET_EXCEEDED);
        db.update("insert into learning.agent_call(job_id,call_key,child_id,local_date,mode,request_hash,state,input_reserved,output_reserved,cost_reserved,created_at) values(?,?,?,?,?,?,'RESERVED',?,?,?,?)",id,key,child,date,run.get("mode"),hash,in,out,cost,ts());
        return Map.of("state","RESERVED","created",true);
    }
    public Object settle(UUID id,String key,JsonNode body) {
        source(id,true);String state=body.path("state").asText();
        if(!Set.of("COMPLETED","FAILED","OUTCOME_UNKNOWN").contains(state))throw new ApiException(ErrorCode.INVALID_SCHEMA);
        var row=db.queryForMap("select state,mode,response_json::text from learning.agent_call where job_id=? and call_key=? for update",id,key);
        if(!"RESERVED".equals(row.get("state")))throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
        boolean paid="LIVE".equals(row.get("mode"));long in=body.path("inputTokens").asLong(-1),out=body.path("outputTokens").asLong(-1);
        if(state.equals("COMPLETED")&&(in<0||out<0||!body.path("response").isObject()))throw new ApiException(ErrorCode.INVALID_SCHEMA);
        boolean known=state.equals("COMPLETED")||state.equals("FAILED");
        // Definitive HTTP rejection consumes a call but no completion tokens. Unknown keeps its full reservation.
        db.update("update learning.agent_call set state=?,input_used=?,output_used=?,cost_used=?,response_json=?::jsonb,provider_request_id=?,settled_at=? where job_id=? and call_key=?",state,
            known?(paid?Math.max(0,in):0):null,known?(paid?Math.max(0,out):0):null,known?(paid?cost(Math.max(0,in),Math.max(0,out)):0):null,
            state.equals("COMPLETED")?body.path("response").toString():null,body.path("providerRequestId").asText(null),ts(),id,key);
        return Map.of("state",state);
    }
    public Object finish(UUID id,JsonNode body) {
        source(id,true);String state=body.path("state").asText();
        if(!Set.of("RESULT_READY","FAILED","OUTCOME_UNKNOWN").contains(state))throw new ApiException(ErrorCode.INVALID_SCHEMA);
        String code=body.path("errorCode").asText(null);if(code!=null&&!code.matches("[A-Z_]{1,80}"))throw new ApiException(ErrorCode.INVALID_SCHEMA);
        db.update("update learning.agent_run set state=?,result_json=?::jsonb,error_code=?,updated_at=? where job_id=? and state='RUNNING'",state,state.equals("RESULT_READY")?body.path("result").toString():null,code,ts(),id);
        return status(id);
    }
    public Object budget(UUID id) {
        source(id,false);var t=db.queryForMap("""
            select count(*) filter(where mode='LIVE') as calls,
            coalesce(sum(input_used),0) as input,coalesce(sum(output_used),0) as output,coalesce(sum(cost_used),0) as cost,
            coalesce(sum(case when input_used is null then input_reserved else 0 end),0) as ri,
            coalesce(sum(case when output_used is null then output_reserved else 0 end),0) as ro,
            coalesce(sum(case when cost_used is null then cost_reserved else 0 end),0) as rc,
            count(*) filter(where state in ('RESERVED','OUTCOME_UNKNOWN')) as unresolved
            from learning.agent_call where job_id=?
            """,id);
        return Map.of("callsUsed",number(t,"calls"),"inputTokensUsed",number(t,"input"),"outputTokensUsed",number(t,"output"),"estimatedCostMicrousd",number(t,"cost"),
            "inputTokensReserved",number(t,"ri"),"outputTokensReserved",number(t,"ro"),"estimatedCostReservedMicrousd",number(t,"rc"),"isFinal",number(t,"unresolved")==0);
    }
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private long cost(long input,long output){return inputPrice.multiply(BigDecimal.valueOf(input)).add(outputPrice.multiply(BigDecimal.valueOf(output))).setScale(0,RoundingMode.CEILING).longValueExact();}
    private Timestamp ts(){return Timestamp.from(clock.instant());}
}
