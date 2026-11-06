package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.common.security.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
@RestController
public class PrototypeProgressController {
    private final PrototypeProgressService service;private final CurrentActor actors;
    public PrototypeProgressController(PrototypeProgressService service,CurrentActor actors){this.service=service;this.actors=actors;}
    @GetMapping("/api/v1/sessions/{session}/observations/cursor") public ResponseEntity<Object> cursor(@PathVariable UUID session){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.cursor(actors.require(ActorPrincipal.Kind.CHILD),session));}
    @PostMapping("/api/v1/sessions/{session}/observations") public ResponseEntity<Object> observations(@PathVariable UUID session,@RequestBody JsonNode body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.observations(actors.require(ActorPrincipal.Kind.CHILD),session,body));}
    @GetMapping("/api/v1/children/{child}/prototype-dashboard") public ResponseEntity<Object> dashboard(@PathVariable UUID child,@RequestParam LocalDate localDate){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.dashboard(actors.require(ActorPrincipal.Kind.ADULT),child,localDate));}
}
