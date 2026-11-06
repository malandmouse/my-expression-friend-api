package com.planb.emotionfriend.api.agent;
import com.planb.emotionfriend.api.common.error.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.JsonNode;
@RestController
@ConditionalOnProperty(name="emotion-friend.prototype.test-clock-enabled",havingValue="true")
public class PrototypeCalendarController {
    private final PrototypeCalendar calendar;public PrototypeCalendarController(PrototypeCalendar calendar){this.calendar=calendar;}
    @GetMapping("/internal/v2/agent-storage/calendar") public ResponseEntity<Object> status(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calendar.status());}
    @PostMapping("/internal/v2/agent-storage/calendar/advance") public ResponseEntity<Object> advance(@RequestBody JsonNode body){
        if(!body.path("expectedVersion").canConvertToLong()||body.path("expectedVersion").asLong()<0)throw new ApiException(ErrorCode.INVALID_SCHEMA);
        UUID id;try{id=UUID.fromString(body.path("requestId").asText());}catch(Exception invalid){throw new ApiException(ErrorCode.INVALID_SCHEMA);}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calendar.advance(id,body.path("expectedVersion").asLong()));
    }
}
