package com.planb.emotionfriend.api.agent;

import com.planb.emotionfriend.api.common.security.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/children/{child}")
public class ScenarioInputController {
    private final ScenarioInputService service;private final CurrentActor actors;
    public ScenarioInputController(ScenarioInputService service,CurrentActor actors){this.service=service;this.actors=actors;}
    private ActorPrincipal actor(){return actors.require(ActorPrincipal.Kind.ADULT);}
    private ResponseEntity<Object> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping("/scenario-profile") public ResponseEntity<Object> profile(@PathVariable UUID child){return ok(service.profile(actor(),child));}
    @PutMapping("/scenario-profile") public ResponseEntity<byte[]> profile(@PathVariable UUID child,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){return service.putProfile(actor(),child,body,key).toResponseEntity();}
    @GetMapping("/daily-events") public ResponseEntity<Object> events(@PathVariable UUID child,@RequestParam(required=false) UUID cursor){return ok(service.events(actor(),child,cursor));}
    @PostMapping("/daily-events") public ResponseEntity<byte[]> create(@PathVariable UUID child,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){return service.writeEvent(actor(),child,null,body,key).toResponseEntity();}
    @PutMapping("/daily-events/{id}") public ResponseEntity<byte[]> update(@PathVariable UUID child,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){return service.writeEvent(actor(),child,id,body,key).toResponseEntity();}
    @DeleteMapping("/daily-events/{id}") public ResponseEntity<byte[]> delete(@PathVariable UUID child,@PathVariable UUID id,@RequestParam long expectedVersion,@RequestHeader("Idempotency-Key") String key){return service.deleteEvent(actor(),child,id,expectedVersion,key).toResponseEntity();}
}
