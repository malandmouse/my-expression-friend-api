package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.agent.PrototypeDays;
import com.planb.emotionfriend.api.common.security.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
@RestController
public class PrototypeHomeController {
    private final CurrentActor actors;private final RuntimeCredentials auth;private final PrototypeDays days;private final JdbcTemplate db;
    public PrototypeHomeController(CurrentActor actors,RuntimeCredentials auth,PrototypeDays days,JdbcTemplate db){this.actors=actors;this.auth=auth;this.days=days;this.db=db;}
    private ActorPrincipal child(){var a=actors.require(ActorPrincipal.Kind.CHILD);auth.require(a);return a;}
    @GetMapping("/api/v1/child-sessions/day-status") @Transactional public ResponseEntity<Object> status(){var a=child();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(days.status(UUID.fromString(a.childId()),days.today()));}
    /** Read-only Day menu. Receipts/session totals remain the source of stars; this never unlocks a Day. */
    @GetMapping("/api/v1/child-sessions/day-index") @Transactional public ResponseEntity<Object> index(){
        var a=child();var today=days.today();
        var rows=db.queryForList("""
            select d.program_day as "programDay",d.local_date as "localDate",d.plan_id as "planId",
              coalesce(s.completed_count,0) as "completedCount",coalesce(s.stars,0) as stars,
              (d.local_date=? and d.plan_id is not null and p.revoked_at is null
               and ((p.day_index=1 and p.unlocked_date is null) or (p.unlocked_date is not null and p.unlocked_date<=?))
               and r.state='READY' and coalesce(s.completed_count,0)<5) as "canLaunch"
            from learning.prototype_day d left join learning.plan p on p.id=d.plan_id
            left join learning.session s on s.plan_id=d.plan_id left join learning.prototype_release r on r.id=d.release_id
            where d.child_id=? order by d.program_day limit 30
            """,java.sql.Date.valueOf(today),java.sql.Date.valueOf(today),UUID.fromString(a.childId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("schemaVersion","EF_DAY_INDEX_P1","childId",a.childId(),"days",rows));
    }
    @GetMapping("/api/v1/child-sessions/capabilities") @Transactional public ResponseEntity<Object> capabilities(){var a=child();var scopes=db.queryForList("select scope from care.consent where child_id=? and granted and withdrawn_at is null",String.class,UUID.fromString(a.childId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("childId",a.childId(),"localDraftResumeAllowed",scopes.contains("LOCAL_DRAFT_RESUME"),"cardsAllowed",scopes.contains("CARD_EXTERNAL_PROCESSING"),"checkpointPlaybackSupported",true));
    }
}
