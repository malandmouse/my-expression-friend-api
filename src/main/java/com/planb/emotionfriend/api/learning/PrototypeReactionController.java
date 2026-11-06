package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.common.security.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/sessions/{session}/loops/{slot}/reaction")
public class PrototypeReactionController {
    private final PrototypeReactionService service;private final CurrentActor actors;
    public PrototypeReactionController(PrototypeReactionService service,CurrentActor actors){this.service=service;this.actors=actors;}
    @GetMapping public ResponseEntity<Object> status(@PathVariable UUID session,@PathVariable int slot){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(actors.require(ActorPrincipal.Kind.CHILD),session,slot));}
    @GetMapping("/assets/{asset}") public ResponseEntity<byte[]> audio(@PathVariable UUID session,@PathVariable int slot,@PathVariable UUID asset,@RequestHeader("X-Expected-SHA256") String hash){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Content-SHA256",hash).body(service.audio(actors.require(ActorPrincipal.Kind.CHILD),session,slot,asset,hash));}
}
