package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.children.ChildAccessPolicy;
import com.planb.emotionfriend.api.children.ChildContracts.ConsentScope;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.membership.MemberGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

/** In-process trusted pipeline port, never exposed as a public controller.
 * The caller must independently review the full material and enumerate its validated paths.
 * Summary confirmation is not that review and cannot call publish(). */
@Service
public class LearningCatalog {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final MemberGuard guard;
    private final ChildAccessPolicy access;
    private final Clock clock;
    public LearningCatalog(JdbcTemplate jdbc, JsonMapper mapper, MemberGuard guard,
            ChildAccessPolicy access, Clock clock) {
        this.jdbc=jdbc; this.mapper=mapper; this.guard=guard; this.access=access; this.clock=clock;
    }

    public record ReviewedPath(List<String> choiceIds, String terminalSceneId,
            List<String> emotionIds, String actorId, Map<String, Boolean> effects) {
        public ReviewedPath {
            if (choiceIds!=null) choiceIds=Collections.unmodifiableList(new ArrayList<>(choiceIds));
            if (emotionIds!=null) emotionIds=Collections.unmodifiableList(new ArrayList<>(emotionIds));
            if (effects!=null) effects=Collections.unmodifiableMap(new TreeMap<>(effects));
        }
    }
    public record ReviewedMaterial(UUID materialId, UUID scenarioVersionId, String subject,
            String summary, String reviewReference, List<ReviewedPath> paths) {}

    @Transactional
    public UUID storeReviewed(UUID child, ReviewedMaterial material) {
        guard.lockDomain();
        requireChild(child);
        validate(material);
        String content=mapper.writeValueAsString(material);
        String hash=hash(content);
        var old=jdbc.queryForList("select content_hash, summary_id, child_id from learning.material where id=?", material.materialId());
        if (!old.isEmpty()) {
            if (!old.getFirst().get("content_hash").equals(hash) || !old.getFirst().get("child_id").equals(child))
                throw new ApiException(ErrorCode.CONTENT_HASH_MISMATCH);
            return (UUID) old.getFirst().get("summary_id");
        }
        UUID summary=UUID.randomUUID();
        jdbc.update("""
                insert into learning.material(id,child_id,summary_id,scenario_version_id,subject,summary,
                    content_hash,review_reference,content,created_at) values(?,?,?,?,?,?,?,?,?::jsonb,?)
                """, material.materialId(),child,summary,material.scenarioVersionId(),material.subject(),
                material.summary(),hash,material.reviewReference(),content,Timestamp.from(clock.instant()));
        return summary;
    }

    @Transactional
    public UUID publish(UUID child, String timezone, List<UUID> materials, String publicationReference) {
        guard.lockDomain();
        requireChild(child);
        if (timezone==null || !ZoneId.getAvailableZoneIds().contains(timezone)
                || !text(publicationReference,200) || materials==null || materials.size()!=5
                || new HashSet<>(materials).size()!=5 || materials.stream().anyMatch(Objects::isNull)) invalid();
        // Confirmation and independent publication evidence are both necessary.
        for (UUID id: materials) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from learning.material where id=? and child_id=?
                        and current and revoked_at is null and status='CONFIRMED')
                    """,Boolean.class,id,child))) throw new ApiException(ErrorCode.CONTENT_NOT_APPROVED);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from learning.plan where child_id=?)",Boolean.class,child)))
            throw new ApiException(ErrorCode.PLAN_ALREADY_ACTIVE);
        UUID plan=UUID.randomUUID();
        jdbc.update("insert into learning.plan(id,child_id,day_index,timezone,publication_reference,published_at) values(?,?,1,?,?,?)",
                plan,child,timezone,publicationReference,Timestamp.from(clock.instant()));
        for(int slot=1;slot<=5;slot++) jdbc.update("""
                insert into learning.plan_slot(plan_id,slot,material_id,content_hash)
                select ?,?,id,content_hash from learning.material where id=?
                """,plan,slot,materials.get(slot-1));
        return plan;
    }

    /** Supersession never edits an existing version or silently repins a session. */
    @Transactional
    public void retire(UUID child, UUID material) {
        guard.lockDomain();
        jdbc.update("update learning.material set current=false, revoked_at=? where id=? and child_id=?",
                Timestamp.from(clock.instant()),material,child);
    }

    private void requireChild(UUID child) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from care.child where id=? and deleted_at is null)",Boolean.class,child)))
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        access.requireConsent(child,ConsentScope.CORE_PROFILE_LEARNING);
    }
    private static void validate(ReviewedMaterial m) {
        if(m==null || m.materialId()==null || m.scenarioVersionId()==null || !text(m.subject(),40)
                || !text(m.summary(),300) || !text(m.reviewReference(),200)
                || m.paths()==null || m.paths().isEmpty() || m.paths().size()>4096) invalid();
        Set<List<String>> paths=new HashSet<>();
        for(var p:m.paths()) {
            if(p==null || p.choiceIds()==null || p.choiceIds().size()<3 || p.choiceIds().size()>8
                    || p.choiceIds().stream().anyMatch(s->!text(s,128))
                    || new HashSet<>(p.choiceIds()).size()!=p.choiceIds().size() || !paths.add(p.choiceIds())
                    || !text(p.terminalSceneId(),128) || p.emotionIds()==null || p.emotionIds().isEmpty()
                    || p.emotionIds().size()>32 || p.emotionIds().stream().anyMatch(s->!text(s,128))
                    || new HashSet<>(p.emotionIds()).size()!=p.emotionIds().size()
                    || p.actorId()==null || !Set.of("jun","nara").contains(p.actorId()) || p.effects()==null || p.effects().size()>32
                    || p.effects().entrySet().stream().anyMatch(e->!e.getKey().matches("[a-z][a-z0-9_]{0,63}") || e.getValue()==null)) invalid();
        }
    }
    static boolean text(String s,int max) {
        return s!=null && !s.isBlank() && s.codePointCount(0,s.length())<=max
                && Normalizer.isNormalized(s,Normalizer.Form.NFC) && s.codePoints().noneMatch(Character::isISOControl);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void invalid() { throw new ApiException(ErrorCode.INVALID_SCHEMA); }
}
