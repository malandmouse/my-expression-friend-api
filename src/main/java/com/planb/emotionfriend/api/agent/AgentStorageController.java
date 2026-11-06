package com.planb.emotionfriend.api.agent;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
@RestController
@RequestMapping("/internal/v2/agent-storage/jobs/{job}")
public class AgentStorageController {
    private final AgentRunStore store;public AgentStorageController(AgentRunStore store){this.store=store;}
    private ResponseEntity<Object> ok(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    @PostMapping("/run") public ResponseEntity<Object> start(@PathVariable UUID job,@RequestBody JsonNode body){return ok(store.start(job,body.path("input"),body.path("mode").asText()));}
    @GetMapping("/run") public ResponseEntity<Object> status(@PathVariable UUID job){return ok(store.status(job));}
    @PostMapping("/finish") public ResponseEntity<Object> finish(@PathVariable UUID job,@RequestBody JsonNode body){return ok(store.finish(job,body));}
    @GetMapping("/budget") public ResponseEntity<Object> budget(@PathVariable UUID job){return ok(store.budget(job));}
    @PostMapping("/calls/{key}/reserve") public ResponseEntity<Object> reserve(@PathVariable UUID job,@PathVariable String key,@RequestBody JsonNode body){return ok(store.reserve(job,key,body));}
    @PostMapping("/calls/{key}/settle") public ResponseEntity<Object> settle(@PathVariable UUID job,@PathVariable String key,@RequestBody JsonNode body){return ok(store.settle(job,key,body));}
}
