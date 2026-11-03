package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.auth.AuthContracts.Role;
import com.planb.emotionfriend.api.children.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.membership.MemberGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Trusted content port. No public ingest or role-based capability bootstrap. */
@Service
@Transactional
public class CorePublicationService {
    private final JdbcTemplate db;private final JsonMapper json;private final MemberGuard guard;
    private final ChildAccessPolicy access;private final ContentAuthority authority;private final CoreDependencyValidator validator;
    private final CoreScenarioValidator structure;private final Clock clock;private final boolean useEnabled;
    public CorePublicationService(JdbcTemplate db,JsonMapper json,MemberGuard guard,ChildAccessPolicy access,
            ContentAuthority authority,CoreDependencyValidator validator,CoreScenarioValidator structure,Clock clock,
            @Value("${emotion-friend.core.child-use-enabled:false}") boolean useEnabled) {
        this.db=db;this.json=json;this.guard=guard;this.access=access;this.authority=authority;
        this.validator=validator;this.structure=structure;this.clock=clock;this.useEnabled=useEnabled;
    }
    public UUID artifact(CoreDependencyValidator.Artifact artifact) {
        guard.lockDomain();validator.artifact(artifact);
        var old=db.queryForList("select id,bytes_hash from learning.core_artifact where id=? or (kind=? and source_id=?)",artifact.id(),artifact.kind(),artifact.sourceId());
        if(!old.isEmpty()) {
            if(old.size()!=1 || !old.getFirst().get("id").equals(artifact.id()) || !old.getFirst().get("bytes_hash").equals(artifact.bytesHash())) fail(ErrorCode.CONTENT_HASH_MISMATCH);
            return artifact.id();
        }
        db.update("insert into learning.core_artifact values(?,?,?,?,?,?)",artifact.id(),artifact.kind(),artifact.sourceId(),artifact.bytes(),artifact.bytesHash(),ts());
        return artifact.id();
    }
    public UUID validate(UUID material,List<UUID> artifacts,Map<String,String> cues) {
        guard.lockDomain();var m=manuscript(material);requireChild((UUID)m.get("child_id"));
        if(artifacts==null || artifacts.isEmpty() || artifacts.size()>500 || new HashSet<>(artifacts).size()!=artifacts.size() || cues==null || cues.size()>200) fail(ErrorCode.INVALID_SCHEMA);
        String payload=(String)m.get("payload");
        structure.validate(payload,CoreScenarioValidator.Profile.valueOf((String)m.get("validation_profile")));
        var result=validator.validate(payload,artifacts.stream().map(this::readArtifact).toList(),cues);
        var old=db.queryForList("select id from learning.core_dependency_validation where material_id=? and manifest_hash=?",UUID.class,material,result.manifestHash());
        if(!old.isEmpty()) return old.getFirst();
        UUID id=UUID.randomUUID();
        db.update("insert into learning.core_dependency_validation values(?,?,?,?,?,?::jsonb,?,?)",id,material,m.get("payload_hash"),result.manifestHash(),result.manifest(),json.writeValueAsString(result.paths()),CoreDependencyValidator.VERSION,ts());
        return id;
    }
    public UUID review(ActorPrincipal actor,UUID validation,String expectedHash,String reference) {
        guard.lockDomain();var d=dependency(validation);UUID child=child(d);
        guard.require(actor,Role.GUARDIAN,Role.THERAPIST,Role.ADMIN);
        authority.require(actor,child,ContentAuthority.Capability.CONTENT_REVIEW);
        requireChild(child);requireHash(d,expectedHash);revalidate(d);
        return evidence(actor,d,"FULL_REVIEW",reference,List.of());
    }
    public UUID allowChildUse(ActorPrincipal actor,UUID validation,String expectedHash,String reference) {
        guard.lockDomain();var d=dependency(validation);UUID child=child(d);
        access.check(actor,child.toString(),ChildAccessChecker.Capability.MANAGE);guard.recent(actor,Role.GUARDIAN);
        if(!useEnabled) fail(ErrorCode.CONTENT_NOT_APPROVED);
        requireChild(child);requireHash(d,expectedHash);revalidate(d);
        return evidence(actor,d,"CHILD_USE",reference,List.of());
    }
    public UUID publish(ActorPrincipal actor,UUID validation,String expectedHash,UUID review,UUID use,String reference) {
        guard.lockDomain();var d=dependency(validation);UUID child=child(d);
        guard.require(actor,Role.GUARDIAN,Role.THERAPIST,Role.ADMIN);
        authority.require(actor,child,ContentAuthority.Capability.CONTENT_PUBLISH);
        requireChild(child);requireHash(d,expectedHash);revalidate(d);requireConfirmed(d);
        requireEvidence(review,"FULL_REVIEW",d);requireEvidence(use,"CHILD_USE",d);
        return evidence(actor,d,"PUBLICATION",reference,List.of(review,use));
    }
    public UUID plan(ActorPrincipal actor,UUID child,int day,String timezone,List<UUID> publications) {
        guard.lockDomain();guard.require(actor,Role.GUARDIAN,Role.THERAPIST,Role.ADMIN);
        authority.require(actor,child,ContentAuthority.Capability.CONTENT_PUBLISH);requireChild(child);
        if(day<1 || timezone==null || !ZoneId.getAvailableZoneIds().contains(timezone) || publications==null
                || publications.size()!=5 || new HashSet<>(publications).size()!=5) fail(ErrorCode.INVALID_SCHEMA);
        var previous=db.queryForList("select timezone from learning.plan where child_id=?",String.class,child);
        if(previous.stream().anyMatch(z->!z.equals(timezone))) fail(ErrorCode.RESOURCE_STATE_CONFLICT);
        var materials=new HashSet<UUID>();var slots=new ArrayList<Map<String,Object>>();
        for(UUID publication:publications) {
            var d=publication(publication,child);UUID material=(UUID)d.get("material_id");
            if(!materials.add(material)) fail(ErrorCode.INVALID_SCHEMA);
            slots.add(Map.of("slot",slots.size()+1,"publicationId",publication,"materialId",material,"manifestHash",d.get("manifest_hash")));
        }
        long world=world(child);
        // Fixed five-slot plans: every branch must satisfy the following slot's preconditions.
        var states=new ArrayList<Map<String,Object>>();var initial=new TreeMap<String,Object>();
        for(String effects:db.queryForList("select effects::text from learning.story_event where child_id=? order by world_revision",String.class,child))
            json.readTree(effects).properties().forEach(e->initial.put(e.getKey(),e.getValue().isBoolean()?e.getValue().asBoolean():e.getValue().asText()));
        states.add(initial);
        for(var slot:slots) {
            var d=publication((UUID)slot.get("publicationId"),child);var s=json.readTree((String)manuscript((UUID)d.get("material_id")).get("payload"));
            var next=new ArrayList<Map<String,Object>>();
            for(var state:states) {
                for(var condition:s.path("preconditions")) {
                    String key=condition.path("key").asText(),op=condition.path("op").asText();
                    if("NOT_EXISTS".equals(op)) {if(state.containsKey(key))fail(ErrorCode.NARRATIVE_PATH_UNAVAILABLE);}
                    else if(!"EQ".equals(op) || !Objects.equals(state.get(key),condition.path("value").isBoolean()?condition.path("value").asBoolean():condition.path("value").asText()))fail(ErrorCode.NARRATIVE_PATH_UNAVAILABLE);
                }
                for(var path:paths(d)) {var n=new TreeMap<>(state);n.putAll(path.effects());if(!next.contains(n))next.add(n);}
            }
            if(next.size()>256) fail(ErrorCode.NARRATIVE_PATH_UNAVAILABLE);states=next;
        }
        String manifest=json.writeValueAsString(Map.of("schemaVersion","core-plan-1","childId",child,"dayIndex",day,"timezone",timezone,"entryWorldRevision",world,"slots",slots));
        String hash=LearningCatalog.hash(manifest);
        var old=db.queryForList("select id,manifest_hash from learning.plan where child_id=? and day_index=?",child,day);
        if(!old.isEmpty()) {if(!hash.equals(old.getFirst().get("manifest_hash")))fail(ErrorCode.CONTENT_HASH_MISMATCH);return (UUID)old.getFirst().get("id");}
        UUID id=UUID.randomUUID();
        db.update("insert into learning.plan(id,child_id,day_index,timezone,publication_reference,published_at,manifest_hash,manifest) values(?,?,?,?,?,?,?,?)",id,child,day,timezone,"CORE_EVIDENCE",ts(),hash,manifest);
        for(var slot:slots) db.update("insert into learning.plan_slot(plan_id,slot,material_id,content_hash,publication_id) values(?,?,?,?,?)",id,slot.get("slot"),slot.get("materialId"),slot.get("manifestHash"),slot.get("publicationId"));
        return id;
    }
    public void revoke(ActorPrincipal actor,UUID target,String reason) {
        guard.lockDomain();var rows=db.queryForList("select child_id from learning.core_evidence where id=?",UUID.class,target);
        UUID child;
        if(!rows.isEmpty()) {
            child=rows.getFirst();var kind=db.queryForObject("select kind from learning.core_evidence where id=?",String.class,target);
            if("CHILD_USE".equals(kind)) {access.check(actor,child.toString(),ChildAccessChecker.Capability.MANAGE);guard.recent(actor,Role.GUARDIAN);}
            else {guard.require(actor,Role.GUARDIAN,Role.THERAPIST,Role.ADMIN);authority.require(actor,child,"FULL_REVIEW".equals(kind)?ContentAuthority.Capability.CONTENT_REVIEW:ContentAuthority.Capability.CONTENT_PUBLISH);}
        } else { // Artifact revocation is global; no implicit ADMIN authority.
            readArtifact(target);guard.require(actor,Role.GUARDIAN,Role.THERAPIST,Role.ADMIN);
            authority.require(actor,null,ContentAuthority.Capability.CONTENT_PUBLISH);
        }
        if(!LearningCatalog.text(reason,200))fail(ErrorCode.INVALID_SCHEMA);
        db.update("insert into learning.core_revocation values(?,?,?,?) on conflict do nothing",target,UUID.fromString(actor.actorId()),reason,ts());
    }
    public Map<String,Object> publication(UUID id,UUID child) {
        guard.lockDomain();var e=one("select * from learning.core_evidence where id=? and kind='PUBLICATION'",id);
        if(!e.get("child_id").equals(child))fail(ErrorCode.CHILD_ACCESS_DENIED);
        var d=dependency((UUID)e.get("validation_id"));requireEvidence(id,"PUBLICATION",d);requireConfirmed(d);revalidate(d);
        var parents=(java.sql.Array)e.get("parents");
        try {var ids=(UUID[])parents.getArray();if(ids.length!=2)fail(ErrorCode.CONTENT_NOT_APPROVED);requireEvidence(ids[0],"FULL_REVIEW",d);requireEvidence(ids[1],"CHILD_USE",d);}
        catch(java.sql.SQLException ex){throw new IllegalStateException(ex);}
        return d;
    }
    public List<LearningCatalog.ReviewedPath> execution(UUID plan,int slot) {
        var row=one("select p.child_id,p.revoked_at,ps.publication_id,ps.content_hash,p.manifest,p.manifest_hash from learning.plan p join learning.plan_slot ps on ps.plan_id=p.id where p.id=? and ps.slot=?",plan,slot);
        if(row.get("revoked_at")!=null)fail(ErrorCode.CONTENT_REVOKED);
        if(!LearningCatalog.hash((String)row.get("manifest")).equals(row.get("manifest_hash")))fail(ErrorCode.CONTENT_HASH_MISMATCH);
        var d=publication((UUID)row.get("publication_id"),(UUID)row.get("child_id"));
        if(!d.get("manifest_hash").equals(row.get("content_hash")))fail(ErrorCode.CONTENT_HASH_MISMATCH);
        return paths(d);
    }
    public CoreDependencyValidator.Artifact readArtifact(UUID id) {
        var a=one("select * from learning.core_artifact where id=?",id);live(id);
        var result=new CoreDependencyValidator.Artifact(id,(String)a.get("kind"),(String)a.get("source_id"),(byte[])a.get("bytes"),(String)a.get("bytes_hash"));validator.artifact(result);return result;
    }
    public Map<String,Object> dependency(UUID id) {return one("select * from learning.core_dependency_validation where id=?",id);}
    public String hash(UUID validation) {return (String)dependency(validation).get("manifest_hash");}
    public Map<String,Object> manuscript(UUID material) {return one("select * from learning.core_manuscript where material_id=?",material);}
    private void revalidate(Map<String,Object> d) {
        var manifest=json.readTree((String)d.get("manifest"));var artifacts=new ArrayList<CoreDependencyValidator.Artifact>();
        for(var a:manifest.path("artifacts")) {var loaded=readArtifact(UUID.fromString(a.path("id").asText()));if(!loaded.bytesHash().equals(a.path("bytesHash").asText()))fail(ErrorCode.CONTENT_HASH_MISMATCH);artifacts.add(loaded);}
        var cues=new TreeMap<String,String>();manifest.path("cueBindings").properties().forEach(e->cues.put(e.getKey(),e.getValue().asText()));
        var m=manuscript((UUID)d.get("material_id"));
        var result=validator.validate((String)m.get("payload"),artifacts,cues);
        if(!result.manifestHash().equals(d.get("manifest_hash")) || !m.get("payload_hash").equals(d.get("payload_hash"))
                || !json.readTree(json.writeValueAsString(result.paths())).equals(json.readTree(d.get("paths").toString())))fail(ErrorCode.CONTENT_HASH_MISMATCH);
    }
    private UUID evidence(ActorPrincipal actor,Map<String,Object> d,String kind,String reference,List<UUID> parents) {
        if(!LearningCatalog.text(reference,200))fail(ErrorCode.INVALID_SCHEMA);UUID child=child(d);long revision=requireChild(child);UUID id=UUID.randomUUID();
        var old=db.queryForList("select id from learning.core_evidence where validation_id=? and kind=? and actor_id=? and consent_revision=? and reference=? and parents=?",UUID.class,d.get("id"),kind,UUID.fromString(actor.actorId()),revision,reference,parents.toArray(UUID[]::new));
        if(!old.isEmpty()){live(old.getFirst());return old.getFirst();}
        UUID verification=kind.equals("CHILD_USE")?guard.recent(actor,Role.GUARDIAN).verificationRef():null;
        db.update("insert into learning.core_evidence values(?,?,?,?,?,?,?,?,?,?,?)",id,d.get("id"),d.get("manifest_hash"),kind,child,UUID.fromString(actor.actorId()),revision,reference,verification,parents.toArray(UUID[]::new),ts());return id;
    }
    private void requireEvidence(UUID id,String kind,Map<String,Object> d) {
        live(id);var e=one("select * from learning.core_evidence where id=?",id);
        if(!e.get("kind").equals(kind) || !e.get("validation_id").equals(d.get("id")) || !e.get("manifest_hash").equals(d.get("manifest_hash"))
                || !e.get("child_id").equals(child(d)) || ((Number)e.get("consent_revision")).longValue()!=requireChild(child(d)))fail(ErrorCode.CONTENT_NOT_APPROVED);
        UUID actor=(UUID)e.get("actor_id");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from identity.member where id=? and status='ACTIVE' and email_verified_at is not null)",Boolean.class,actor)))fail(ErrorCode.CONTENT_NOT_APPROVED);
        if(!kind.equals("CHILD_USE")) authority.require(new ActorPrincipal(actor.toString(),ActorPrincipal.Kind.ADULT,null,null,null),child(d),kind.equals("FULL_REVIEW")?ContentAuthority.Capability.CONTENT_REVIEW:ContentAuthority.Capability.CONTENT_PUBLISH);
        else if(!actor.equals(db.queryForObject("select guardian_id from care.child where id=?",UUID.class,child(d))))fail(ErrorCode.CONTENT_NOT_APPROVED);
    }
    private void requireConfirmed(Map<String,Object> d) {
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from learning.material where id=? and current and revoked_at is null and status='CONFIRMED')",Boolean.class,d.get("material_id"))))fail(ErrorCode.CONTENT_NOT_APPROVED);
    }
    private UUID child(Map<String,Object> d) {return (UUID)manuscript((UUID)d.get("material_id")).get("child_id");}
    private long requireChild(UUID child) {
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from care.child c join identity.member m on m.id=c.guardian_id where c.id=? and c.deleted_at is null and m.status='ACTIVE' and m.role='GUARDIAN' and m.email_verified_at is not null)",Boolean.class,child)))fail(ErrorCode.CHILD_ACCESS_DENIED);
        access.requireConsent(child,ChildContracts.ConsentScope.CORE_PROFILE_LEARNING);
        return db.queryForObject("select revision from care.consent where child_id=? and scope='CORE_PROFILE_LEARNING'",Long.class,child);
    }
    private long world(UUID child) {return db.queryForObject("select coalesce((select revision from learning.child_world where child_id=?),0)",Long.class,child);}
    private void requireHash(Map<String,Object> d,String hash) {if(!d.get("manifest_hash").equals(hash))fail(ErrorCode.CONTENT_HASH_MISMATCH);}
    private void live(UUID id) {if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from learning.core_revocation where target_id=?)",Boolean.class,id)))fail(ErrorCode.CONTENT_REVOKED);}
    private List<LearningCatalog.ReviewedPath> paths(Map<String,Object> d) {return Arrays.asList(json.readValue(d.get("paths").toString(),LearningCatalog.ReviewedPath[].class));}
    private Map<String,Object> one(String sql,Object...args) {var r=db.queryForList(sql,args);if(r.isEmpty())fail(ErrorCode.CONTENT_NOT_APPROVED);return r.getFirst();}
    private Timestamp ts() {return Timestamp.from(clock.instant());}
    private static void fail(ErrorCode code) {throw new ApiException(code);}
}
