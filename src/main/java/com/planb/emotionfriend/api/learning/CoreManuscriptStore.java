package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.children.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.membership.MemberGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/** Trusted in-process admission port, with no controller and no generation/publication side effects. */
@Service
public class CoreManuscriptStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final CoreScenarioValidator validator;
    private final MemberGuard guard;
    private final ChildAccessPolicy children;
    private final Clock clock;
    public CoreManuscriptStore(JdbcTemplate jdbc,JsonMapper json,CoreScenarioValidator validator,
            MemberGuard guard,ChildAccessPolicy children,Clock clock) {
        this.jdbc=jdbc;this.json=json;this.validator=validator;this.guard=guard;this.children=children;this.clock=clock;
    }
    public record Draft(UUID materialId,UUID scenarioVersionId,String sourceMaterialId,String subject,
            String summary,String payload,CoreScenarioValidator.Profile profile) {
        @Override public String toString() { return "Draft[manuscript redacted]"; }
    }
    @Transactional
    public UUID store(UUID child,Draft draft) {
        guard.lockDomain();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from care.child where id=? and deleted_at is null)",Boolean.class,child)))
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        children.requireConsent(child,ChildContracts.ConsentScope.CORE_PROFILE_LEARNING);
        if(draft==null || draft.materialId()==null || draft.scenarioVersionId()==null
                || !LearningCatalog.text(draft.sourceMaterialId(),256) || !LearningCatalog.text(draft.subject(),40)
                || !LearningCatalog.text(draft.summary(),300)) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        var validated=validator.validate(draft.payload(),draft.profile());
        String admissionHash=LearningCatalog.hash(json.writeValueAsString(List.of(child,draft.materialId(),
                draft.scenarioVersionId(),draft.sourceMaterialId(),draft.subject(),draft.summary(),draft.profile(),validated.payloadHash())));
        var old=jdbc.queryForList("""
                select c.admission_hash,c.material_id,m.summary_id from learning.core_manuscript c
                join learning.material m on m.id=c.material_id
                where c.material_id=? or c.scenario_version_id=? or c.source_scenario_version_id=?
                """,draft.materialId(),draft.scenarioVersionId(),validated.sourceScenarioVersionId());
        if(!old.isEmpty()) {
            if(old.size()!=1 || !admissionHash.equals(old.getFirst().get("admission_hash")))
                throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
            return (UUID)old.getFirst().get("summary_id");
        }
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from learning.material where id=? or scenario_version_id=?)",Boolean.class,draft.materialId(),draft.scenarioVersionId())))
            throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
        UUID summary=UUID.randomUUID();var now=Timestamp.from(clock.instant());
        // A summary projection is deliberately non-executable; original manuscript remains the source.
        var projection=new LearningCatalog.ReviewedMaterial(draft.materialId(),draft.scenarioVersionId(),
                draft.subject(),draft.summary(),"CORE_DRAFT_STRUCTURE_ONLY",List.of());
        String content=json.writeValueAsString(projection);
        jdbc.update("""
                insert into learning.material(id,child_id,summary_id,scenario_version_id,subject,summary,content_hash,
                    review_reference,content,content_origin,created_at) values(?,?,?,?,?,?,?, ?,?::jsonb,'CORE_DRAFT',?)
                """,draft.materialId(),child,summary,draft.scenarioVersionId(),draft.subject(),draft.summary(),
                LearningCatalog.hash(content),projection.reviewReference(),content,now);
        jdbc.update("""
                insert into learning.core_manuscript(material_id,child_id,scenario_version_id,source_material_id,
                    source_scenario_version_id,source_content_hash,payload_hash,payload,admission_hash,validation_profile,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?)
                """,draft.materialId(),child,draft.scenarioVersionId(),draft.sourceMaterialId(),validated.sourceScenarioVersionId(),
                validated.sourceContentHash(),validated.payloadHash(),validated.payload(),admissionHash,draft.profile().name(),now);
        jdbc.update("""
                insert into learning.core_manuscript_validation(material_id,validated_hash,validator_version,report,validated_at)
                values(?,?,?,?::jsonb,?)
                """,draft.materialId(),validated.payloadHash(),CoreScenarioValidator.VERSION,json.writeValueAsString(validated.report()),now);
        return summary;
    }
}
