package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.common.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Explicit version evidence. All operational capability adapters default to deny. */
@RestController
public class CoreContentController {
    private final CorePublicationService content;private final CurrentActor actors;
    public CoreContentController(CorePublicationService content,CurrentActor actors){this.content=content;this.actors=actors;}
    public record Evidence(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String manifestHash,@NotBlank @Size(max=200) String reference,
            @NotNull @AssertTrue Boolean allBranchesAndMediaReviewed) {}
    public record Publish(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String manifestHash,@NotNull UUID reviewId,@NotNull UUID childUseId,@NotBlank @Size(max=200) String reference) {}
    public record ChildUse(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String manifestHash,@NotBlank @Size(max=200) String reference,@NotNull @AssertTrue Boolean childUseAllowed) {}
    public record Plan(@Min(1) int dayIndex,@NotBlank String timezone,@NotNull @Size(min=5,max=5) List<@NotNull UUID> publicationIds) {}
    public record Revoke(@NotBlank @Size(max=200) String reason) {}
    private ActorPrincipal actor(){return actors.require(ActorPrincipal.Kind.ADULT);}
    private ResponseEntity<?> created(UUID id){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(Map.of("evidenceId",id));}
    @PostMapping("/api/v1/core/validations/{validationId}/full-review")
    public ResponseEntity<?> review(@PathVariable UUID validationId,@Valid @RequestBody Evidence body){return created(content.review(actor(),validationId,body.manifestHash(),body.reference()));}
    @PostMapping("/api/v1/core/validations/{validationId}/child-use")
    public ResponseEntity<?> use(@PathVariable UUID validationId,@Valid @RequestBody ChildUse body){return created(content.allowChildUse(actor(),validationId,body.manifestHash(),body.reference()));}
    @PostMapping("/api/v1/core/validations/{validationId}/publish")
    public ResponseEntity<?> publish(@PathVariable UUID validationId,@Valid @RequestBody Publish body){return created(content.publish(actor(),validationId,body.manifestHash(),body.reviewId(),body.childUseId(),body.reference()));}
    @PostMapping("/api/v1/children/{childId}/core-plans")
    public ResponseEntity<?> plan(@PathVariable UUID childId,@Valid @RequestBody Plan body){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(Map.of("planId",content.plan(actor(),childId,body.dayIndex(),body.timezone(),body.publicationIds())));}
    @PostMapping("/api/v1/core/evidence/{evidenceId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable UUID evidenceId,@Valid @RequestBody Revoke body){content.revoke(actor(),evidenceId,body.reason());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
}
