package com.planb.emotionfriend.api.agent;

import com.planb.emotionfriend.api.children.ChildContracts.ConsentScope;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.learning.LearningCurrent;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Only accepted foundation receipts are summarized. Missing recognition observations are never invented. */
@Service
public class AgentSummaryService {
    private final JdbcTemplate db;private final JsonMapper json;private final LearningCurrent current;
    private final AgentWorkerClient worker;private final TransactionTemplate tx;private final Clock clock;
    public AgentSummaryService(JdbcTemplate db,JsonMapper json,LearningCurrent current,AgentWorkerClient worker,PlatformTransactionManager manager,Clock clock){
        this.db=db;this.json=json;this.current=current;this.worker=worker;this.tx=new TransactionTemplate(manager);this.clock=clock;
    }
    public Object summarize(ActorPrincipal actor,UUID child) {
        worker.requireEnabled();
        var query=new LearningCurrent.Query(actor,child,ChildAccessChecker.Capability.VIEW_SUMMARY,null,Set.of(ConsentScope.CORE_PROFILE_LEARNING));
        var now=clock.instant();var start=now.minus(Duration.ofDays(30));
        record Source(LearningCurrent.Snapshot pin,JsonNode input) {}
        Source source=tx.execute(status->{
            var pin=current.read(query);var request=json.createObjectNode();
            request.put("contractVersion","EF_ST_AGENT_DRAFT_V1").put("childId",child.toString())
                .put("observationSnapshotId",UUID.randomUUID().toString()).put("observationRevision",1);
            request.putObject("period").put("startInclusive",start.toString()).put("endExclusive",now.toString());
            var receipts=request.putArray("receipts");
            var rows=db.queryForList("""
                select r.response::text from learning.receipt r join learning.plan p on p.id=r.plan_id
                where p.child_id=? and r.completed_at>=? and r.completed_at<? order by r.completed_at,r.id limit 151
                """,child,java.sql.Timestamp.from(start),java.sql.Timestamp.from(now));
            if(rows.size()>150)throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
            for(var row:rows){var value=json.readTree((String)row.get("response"));var receipt=receipts.addObject();
                for(String field:List.of("receiptId","sessionId","slot","starsAwarded"))receipt.set(field,value.path(field));}
            request.putArray("observations");return new Source(pin,request);
        });
        JsonNode result=worker.execute("/internal/v1/training-summary",source.input());
        return tx.execute(status->{
            if(!current.read(query).equals(source.pin()))throw new ApiException(ErrorCode.STALE_DRAFT);
            if(!child.toString().equals(result.path("childId").asText()) || !source.input().path("observationSnapshotId").equals(result.path("observationSnapshotId")))
                throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
            var out=(tools.jackson.databind.node.ObjectNode)result.deepCopy();
            out.put("source","FOUNDATION_ACCEPTED_RECEIPTS_ONLY");out.put("observationDataAvailable",false);
            out.putArray("limitations").add("NOT_A_DIAGNOSTIC_SCORE").add("LEGACY_SUMMARY_OMITS_P1_OBSERVATIONS_USE_PROTOTYPE_DASHBOARD")
                .add("NO_AUTOMATIC_PROFILE_CHANGE").add("RECEIPTS_ARE_COMPLETION_EVIDENCE_NOT_RELATIONSHIP_OR_LANGUAGE_ABILITY");
            return out;
        });
    }
}
