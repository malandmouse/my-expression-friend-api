package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.common.error.*;
import java.util.*;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Called only inside the existing completion transaction, after the server validates the selected path. */
final class ReceiptPaths {
    private ReceiptPaths() {}
    static void record(JdbcTemplate db,JsonMapper json,LearningContracts.Session state,int slot,
            LearningContracts.FinalizeRequest request,LearningContracts.Receipt receipt,String actor) {
        var material=state.slots().stream().filter(s->s.slot()==slot).findFirst().orElseThrow();
        var rows=db.queryForList("select payload from learning.core_manuscript where scenario_version_id=? and child_id=?",String.class,material.scenarioVersionId(),state.childId());
        var visited=new ArrayList<String>();var heard=new ArrayList<String>();
        if(!rows.isEmpty()) {
            var body=json.readTree(rows.getFirst());var scenes=new HashMap<String,JsonNode>();
            body.path("sceneGraph").path("scenes").forEach(s->scenes.put(s.path("sceneId").asText(),s));
            String id=body.path("sceneGraph").path("startSceneId").asText();int index=0;
            for(int count=0;count<=scenes.size();count++) {
                var scene=scenes.get(id);if(scene==null||visited.contains(id))throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);
                visited.add(id);heard.add(scene.path("dialogue").asText());
                if(scene.path("end").asBoolean())break;
                if(scene.has("nextSceneId")){id=scene.path("nextSceneId").asText();continue;}
                if(index>=request.selectedChoiceIds().size())throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);
                String choice=request.selectedChoiceIds().get(index++);
                var selected=scene.path("choices").valueStream().filter(c->c.path("choiceId").asText().equals(choice)).findFirst().orElseThrow(()->new ApiException(ErrorCode.INVALID_BRANCH_PATH));
                id=selected.path("nextSceneId").asText();
            }
            if(!visited.getLast().equals(request.terminalSceneId())||index!=request.selectedChoiceIds().size())throw new ApiException(ErrorCode.INVALID_BRANCH_PATH);
        }
        db.update("""
            insert into learning.receipt_path(receipt_id,child_id,scenario_version_id,terminal_scene_id,npc_id,choice_ids,scene_ids,heard_lines,recorded_at)
            values(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)
            """,receipt.receiptId(),state.childId(),material.scenarioVersionId(),request.terminalSceneId(),actor.equals("jun")?"JungHoon":"MainPlayer",
            json.writeValueAsString(request.selectedChoiceIds()),json.writeValueAsString(visited),json.writeValueAsString(heard),Timestamp.from(receipt.completedAt()));
    }
}
