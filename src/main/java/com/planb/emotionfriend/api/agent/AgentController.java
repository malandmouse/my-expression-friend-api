package com.planb.emotionfriend.api.agent;

import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.common.idempotency.StoredResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class AgentController {
    private final AgentSummaryService summary;private final AgentJobService jobs;private final CurrentActor actors;private final JsonMapper json;
    public AgentController(AgentJobService jobs,CurrentActor actors,JsonMapper json,AgentSummaryService summary){this.summary=summary;this.jobs=jobs;this.actors=actors;this.json=json;}
    @ExceptionHandler(com.planb.emotionfriend.api.learning.integration.PostgresGenerationJobs.Conflict.class)
    public ResponseEntity<Object> conflict(){return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(java.util.Map.of("code","RESOURCE_STATE_CONFLICT","retryable",false));}
    private ActorPrincipal actor(){return actors.require(ActorPrincipal.Kind.ADULT);}
    private ResponseEntity<Object> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping("/api/v1/children/{child}/agent-training-summary")
    public ResponseEntity<Object> summary(@PathVariable UUID child){return ok(summary.summarize(actor(),child));}
    @PostMapping("/api/v1/children/{child}/agent-jobs")
    public ResponseEntity<byte[]> generate(@PathVariable UUID child,@RequestBody tools.jackson.databind.JsonNode body,@RequestHeader("Idempotency-Key") String key) {
        StoredResponse response;
        if(body.path("schemaVersion").asText().equals("EF_GENERATE_DAY_P1"))response=jobs.reserveP1(actor(),child,body,key);
        else {
            AgentContracts.Generate legacy;
            try{legacy=json.treeToValue(body,AgentContracts.Generate.class);}catch(Exception invalid){throw new com.planb.emotionfriend.api.common.error.ApiException(com.planb.emotionfriend.api.common.error.ErrorCode.INVALID_SCHEMA);}
            // Bean validation is applied explicitly because the endpoint also accepts the versioned P1 command.
            try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                if(!factory.getValidator().validate(legacy).isEmpty())throw new com.planb.emotionfriend.api.common.error.ApiException(com.planb.emotionfriend.api.common.error.ErrorCode.INVALID_SCHEMA);
            }
            response=jobs.reserve(actor(),child,legacy,key);
        }
        jobs.enqueue(UUID.fromString(json.readTree(response.body()).path("jobId").asText()));return response.toResponseEntity();
    }
    @GetMapping("/api/v1/children/{child}/agent-jobs") public ResponseEntity<Object> list(@PathVariable UUID child){return ok(jobs.list(actor(),child));}
    @GetMapping("/api/v1/children/{child}/agent-capabilities") public ResponseEntity<Object> capabilities(@PathVariable UUID child){return ok(jobs.capabilities(actor(),child));}
    @GetMapping("/api/v1/children/{child}/agent-jobs/{id}")
    public ResponseEntity<Object> status(@PathVariable UUID child,@PathVariable UUID id){return ok(jobs.status(actor(),child,id));}
    @GetMapping("/api/v1/children/{child}/agent-jobs/{id}/summaries")
    public ResponseEntity<Object> summaries(@PathVariable UUID child,@PathVariable UUID id){return ok(jobs.summaries(actor(),child,id));}
    @PostMapping("/api/v1/children/{child}/agent-jobs/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable UUID child,@PathVariable UUID id,@Valid @RequestBody AgentContracts.Cancel body){return ok(jobs.cancel(actor(),child,id,body.expectedVersion()));}
    @PostMapping("/api/v1/children/{child}/agent-jobs/{id}/dispatch")
    public ResponseEntity<Object> dispatch(@PathVariable UUID child,@PathVariable UUID id){jobs.dispatchAuthorized(actor(),child,id);return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(java.util.Map.of("jobId",id));}
}
