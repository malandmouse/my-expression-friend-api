package com.planb.emotionfriend.api.learning;
import com.planb.emotionfriend.api.agent.PrototypeJson;
import com.planb.emotionfriend.api.common.error.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual current path only. Checkpoint revision/proof changes alone do not change the expression context. */
public final class PrototypeExpressionContext {
    private PrototypeExpressionContext() {}
    public static JsonNode identity(JdbcTemplate db,JsonMapper json,UUID session,int slot) {
        var row=db.queryForMap("select c.attempt_id,c.slot,c.body::text,p.plan_hash,s.plan_id,ps.scenario_version_id from learning.runtime_checkpoint c join learning.session s on s.id=c.session_id join learning.session_pin p on p.session_id=c.session_id join (select p.plan_id,p.slot,m.scenario_version_id from learning.plan_slot p join learning.material m on m.id=p.material_id) ps on ps.plan_id=s.plan_id and ps.slot=c.slot where c.session_id=?",session);
        if(((Number)row.get("slot")).intValue()!=slot)throw new ApiException(ErrorCode.STALE_CHECKPOINT);
        var cp=json.readTree((String)row.get("body"));
        if(cp.path("terminalSceneId").isNull()||cp.path("terminalSceneId").isMissingNode()||cp.path("selectedEmotionId").isNull()||cp.path("selectedEmotionId").isMissingNode())throw new ApiException(ErrorCode.STALE_CHECKPOINT);
        var out=json.createObjectNode().put("sessionId",session.toString()).put("slot",slot).put("attemptId",row.get("attempt_id").toString()).put("planId",row.get("plan_id").toString()).put("manifestHash",(String)row.get("plan_hash")).put("scenarioVersionId",row.get("scenario_version_id").toString());
        for(String field:List.of("selectedChoiceIds","terminalSceneId","selectedEmotionId"))out.set(field,cp.path(field));return out;
    }
    public static String hash(JdbcTemplate db,JsonMapper json,UUID session,int slot){return PrototypeJson.digest(identity(db,json,session,slot));}
}
