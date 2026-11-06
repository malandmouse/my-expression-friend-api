package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.common.error.*;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

final class PrototypeExpressionProof {
    private PrototypeExpressionProof() {}
    static void require(JdbcTemplate db,JsonMapper json,ConfirmedExpressions legacy,Clock clock,UUID id,UUID session,int slot,String actor,long version,String choice) {
        var rows=db.queryForList("select * from learning.prototype_expression_proof where id=?",id);
        if(rows.isEmpty()){if(!choice.equals("ORIGINAL"))throw new ApiException(ErrorCode.STALE_DRAFT);legacy.require(id,session,slot,actor,version);return;}
        var p=rows.getFirst();
        long currentVersion=db.queryForObject("select (body->>'draftVersion')::bigint from learning.runtime_checkpoint where session_id=?",Long.class,session);
        if(!p.get("session_id").equals(session)||((Number)p.get("slot")).intValue()!=slot||!p.get("actor_id").equals(actor)||((Number)p.get("draft_version")).longValue()!=version||currentVersion!=version
            ||!p.get("expression_choice").equals(choice)||!((Timestamp)p.get("expires_at")).toInstant().isAfter(clock.instant())||!p.get("context_hash").equals(PrototypeExpressionContext.hash(db,json,session,slot)))throw new ApiException(ErrorCode.STALE_DRAFT);
    }
    static String choice(JdbcTemplate db,UUID id){var rows=db.queryForList("select expression_choice from learning.prototype_expression_proof where id=?",String.class,id);return rows.isEmpty()?"ORIGINAL":rows.getFirst();}
}
