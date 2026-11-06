package com.planb.emotionfriend.api.learning;

import static com.planb.emotionfriend.api.learning.RuntimeContracts.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class RuntimeController {
    private final RuntimeCredentials auth;private final RuntimeLearningService learning;private final CurrentActor actors;private final CoreMediaDelivery media;private final PrototypeExpressionService p1;
    public RuntimeController(RuntimeCredentials auth,RuntimeLearningService learning,CurrentActor actors,CoreMediaDelivery media,PrototypeExpressionService p1) {this.auth=auth;this.learning=learning;this.actors=actors;this.media=media;this.p1=p1;}
    private ActorPrincipal child(){return actors.require(ActorPrincipal.Kind.CHILD);}
    private ActorPrincipal adult(){return actors.require(ActorPrincipal.Kind.ADULT);}
    private ResponseEntity<Object> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @PostMapping("/api/v1/children/{childId}/device-grants")
    public ResponseEntity<byte[]> grant(@PathVariable UUID childId,@Valid @RequestBody DeviceRequest body,@RequestHeader("Idempotency-Key") String key){return auth.grant(adult(),childId,body,key).toResponseEntity();}
    @DeleteMapping("/api/v1/children/{childId}/devices/{deviceId}")
    public ResponseEntity<byte[]> revoke(@PathVariable UUID childId,@PathVariable String deviceId,@RequestHeader("Idempotency-Key") String key){return auth.revoke(adult(),childId,deviceId,key).toResponseEntity();}
    @PostMapping("/api/v1/child-sessions/exchange")
    public ResponseEntity<byte[]> exchange(@Valid @RequestBody Exchange body,@RequestHeader("Idempotency-Key") String key){return auth.exchange(body,key).toResponseEntity();}
    @PostMapping("/api/v1/child-sessions/refresh")
    public ResponseEntity<byte[]> refresh(@Valid @RequestBody Refresh body,@RequestHeader("Idempotency-Key") String key){return auth.refresh(body,key).toResponseEntity();}
    @PostMapping("/api/v1/child-sessions/renew")
    public ResponseEntity<byte[]> renew(@Valid @RequestBody DeviceRequest body,@RequestHeader("Idempotency-Key") String key){return auth.renew(child(),body,key,false).toResponseEntity();}
    @PostMapping("/api/v1/child-sessions/persist")
    public ResponseEntity<byte[]> persist(@Valid @RequestBody DeviceRequest body,@RequestHeader("Idempotency-Key") String key){return auth.renew(child(),body,key,true).toResponseEntity();}
    @GetMapping("/api/v1/children/{childId}/learning-home")
    public ResponseEntity<Object> home(@PathVariable UUID childId){return ok(learning.home(child(),childId));}
    @PostMapping("/api/v1/children/{childId}/mission-entries")
    public ResponseEntity<byte[]> enter(@PathVariable UUID childId,@Valid @RequestBody Entry body,@RequestHeader("Idempotency-Key") String key){return learning.enter(child(),childId,body,key).toResponseEntity();}
    @PostMapping("/api/v1/daily-plans/{planId}/sessions")
    public ResponseEntity<byte[]> start(@PathVariable UUID planId,@Valid @RequestBody Start body,@RequestHeader("Idempotency-Key") String key){return learning.start(child(),planId,body,key).toResponseEntity();}
    @GetMapping("/api/v1/sessions/{sessionId}")
    public ResponseEntity<Object> session(@PathVariable UUID sessionId){return ok(learning.session(child(),sessionId));}
    @PutMapping("/api/v1/sessions/{sessionId}/checkpoint")
    public ResponseEntity<byte[]> checkpoint(@PathVariable UUID sessionId,@Valid @RequestBody Checkpoint body,@RequestHeader("Idempotency-Key") String key){return learning.checkpoint(child(),sessionId,body,key).toResponseEntity();}
    @PutMapping("/api/v1/sessions/{sessionId}/checkpoint-and-playback")
    public ResponseEntity<byte[]> checkpointPlayback(@PathVariable UUID sessionId,@Valid @RequestBody CheckpointPlayback body,@RequestHeader("Idempotency-Key") String key){return learning.checkpointPlayback(child(),sessionId,body,key).toResponseEntity();}
    @PostMapping("/api/v1/sessions/{sessionId}/slots/{slot}/confirmed-expressions")
    public ResponseEntity<byte[]> expression(@PathVariable UUID sessionId,@PathVariable int slot,@RequestBody tools.jackson.databind.JsonNode body,@RequestHeader("Idempotency-Key") String key){if(body.has("schemaVersion"))return p1.confirm(child(),sessionId,slot,body,key).toResponseEntity();
        var old=new tools.jackson.databind.json.JsonMapper().treeToValue(body,LearningContracts.ExpressionRequest.class);try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){if(!factory.getValidator().validate(old).isEmpty())throw new ApiException(ErrorCode.INVALID_SCHEMA);}return learning.expression(child(),sessionId,slot,old,key).toResponseEntity();}
    @GetMapping("/api/v1/sessions/{sessionId}/slots/{slot}/expression-context")
    public ResponseEntity<Object> context(@PathVariable UUID sessionId,@PathVariable int slot){return ok(p1.context(child(),sessionId,slot));}
    @GetMapping("/api/v1/sessions/{sessionId}/slots/{slot}/expression-options/{offer}")
    public ResponseEntity<Object> offer(@PathVariable UUID sessionId,@PathVariable int slot,@PathVariable UUID offer){return ok(p1.offer(child(),sessionId,slot,offer));}
    @PostMapping("/api/v1/sessions/{sessionId}/slots/{slot}/expression-options")
    public ResponseEntity<Object> options(@PathVariable UUID sessionId,@PathVariable int slot,@RequestBody tools.jackson.databind.JsonNode body,@RequestHeader("Idempotency-Key") String key){return ok(p1.generate(child(),sessionId,slot,body,key,true));}
    @PostMapping("/api/v1/sessions/{sessionId}/slots/{slot}/expression-expansions")
    public ResponseEntity<Object> expand(@PathVariable UUID sessionId,@PathVariable int slot,@RequestBody tools.jackson.databind.JsonNode body,@RequestHeader("Idempotency-Key") String key){return ok(p1.generate(child(),sessionId,slot,body,key,false));}
    @PostMapping("/api/v1/sessions/{sessionId}/loops/{slot}/finalize")
    public ResponseEntity<byte[]> complete(@PathVariable UUID sessionId,@PathVariable int slot,@Valid @RequestBody Commit body,@RequestHeader("Idempotency-Key") String key){return learning.complete(child(),sessionId,slot,body,key).toResponseEntity();}
    @GetMapping("/api/v1/sessions/{sessionId}/loops/{slot}/receipt")
    public ResponseEntity<Object> receipt(@PathVariable UUID sessionId,@PathVariable int slot){return ok(learning.receipt(child(),sessionId,slot));}
    @GetMapping("/api/v1/sessions/{sessionId}/slots/{slot}/content")
    public ResponseEntity<Object> content(@PathVariable UUID sessionId,@PathVariable int slot){return ok(learning.content(child(),sessionId,slot));}
    @GetMapping("/api/v1/sessions/{sessionId}/slots/{slot}/package")
    public ResponseEntity<Object> runtimePackage(@PathVariable UUID sessionId,@PathVariable int slot){return ok(learning.runtimePackage(child(),sessionId,slot));}
    @PostMapping("/api/v1/sessions/{sessionId}/playback-leases")
    public ResponseEntity<byte[]> lease(@PathVariable UUID sessionId,@Valid @RequestBody LeaseRequest body,@RequestHeader("Idempotency-Key") String key){return learning.lease(child(),sessionId,body,key).toResponseEntity();}
    @GetMapping("/api/v1/sessions/{sessionId}/content/{slot}/assets/{assetId}")
    public ResponseEntity<byte[]> asset(@PathVariable UUID sessionId,@PathVariable int slot,@PathVariable UUID assetId,@RequestHeader("X-Playback-Lease") UUID lease) {
        var actor=child();String hash=learning.authorizeAsset(actor,sessionId,slot,assetId,lease);
        byte[] bytes=media.read(assetId);if(!CoreDependencyValidator.sha(bytes).equals(hash))throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
        learning.authorizeAsset(actor,sessionId,slot,assetId,lease);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-SHA256",hash).contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);
    }
    @PostMapping("/api/v1/playback-leases/{leaseId}/renew")
    public ResponseEntity<byte[]> renewLease(@PathVariable UUID leaseId,@Valid @RequestBody LeaseRequest body,@RequestHeader("Idempotency-Key") String key){return learning.renewLease(child(),leaseId,body,key).toResponseEntity();}
}
