package com.planb.emotionfriend.api.agent;
import com.planb.emotionfriend.api.common.security.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
@RestController
@RequestMapping("/api/v1/children/{child}")
public class PrototypeReleaseController {
    private final PrototypeReleaseService service;private final CurrentActor actors;private final JsonMapper json;
    public PrototypeReleaseController(PrototypeReleaseService service,CurrentActor actors,JsonMapper json){this.service=service;this.actors=actors;this.json=json;}
    private ActorPrincipal actor(){return actors.require(ActorPrincipal.Kind.ADULT);}
    @PostMapping("/agent-jobs/{job}/day-decision") public ResponseEntity<byte[]> decide(@PathVariable UUID child,@PathVariable UUID job,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){
        var result=service.decide(actor(),child,job,body,key);var parsed=json.readTree(result.body());if(parsed.path("state").asText().equals("CONFIRMED_MEDIA_PENDING"))service.enqueue(UUID.fromString(parsed.path("releaseId").asText()));return result.toResponseEntity();
    }
    @PostMapping("/day-releases/{id}/retry-publication") public ResponseEntity<byte[]> retryPublication(@PathVariable UUID child,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){var result=service.retryPublication(actor(),child,id,body,key);service.enqueue(id);return result.toResponseEntity();}
    @GetMapping("/day-releases/{id}") public ResponseEntity<Object> status(@PathVariable UUID child,@PathVariable UUID id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(actor(),child,id));}
    @PostMapping("/day-releases/{id}/retry-media") public ResponseEntity<byte[]> retry(@PathVariable UUID child,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){
        var result=service.retry(actor(),child,id,body,key);service.enqueue(id);return result.toResponseEntity();
    }
}
