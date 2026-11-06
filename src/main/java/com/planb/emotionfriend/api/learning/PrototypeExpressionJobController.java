package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.common.security.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
@RestController
@RequestMapping("/api/v1/sessions/{session}/slots/{slot}/expression-jobs")
public class PrototypeExpressionJobController {
    private final PrototypeExpressionJobs jobs;private final CurrentActor actors;
    public PrototypeExpressionJobController(PrototypeExpressionJobs jobs,CurrentActor actors){this.jobs=jobs;this.actors=actors;}
    @PostMapping public ResponseEntity<Object> create(@PathVariable UUID session,@PathVariable int slot,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(jobs.create(actors.require(ActorPrincipal.Kind.CHILD),session,slot,body,key));}
    @GetMapping("/{id}") public ResponseEntity<Object> status(@PathVariable UUID session,@PathVariable int slot,@PathVariable UUID id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.status(actors.require(ActorPrincipal.Kind.CHILD),session,slot,id));}
}
