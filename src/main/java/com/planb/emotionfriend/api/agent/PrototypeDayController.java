package com.planb.emotionfriend.api.agent;
import com.planb.emotionfriend.api.common.security.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@RestController
public class PrototypeDayController {
    private final PrototypeDays days;private final CurrentActor actors;
    public PrototypeDayController(PrototypeDays days,CurrentActor actors){this.days=days;this.actors=actors;}
    @GetMapping("/api/v1/children/{child}/day-status") public ResponseEntity<Object> status(@PathVariable UUID child,@RequestParam(required=false) LocalDate localDate){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(days.adultStatus(actors.require(ActorPrincipal.Kind.ADULT),child,localDate));}
}
