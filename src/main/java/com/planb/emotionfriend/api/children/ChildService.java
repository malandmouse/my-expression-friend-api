package com.planb.emotionfriend.api.children;

import static com.planb.emotionfriend.api.children.ChildContracts.*;
import static com.planb.emotionfriend.api.common.security.ChildAccessChecker.Capability.*;

import com.planb.emotionfriend.api.auth.AuthContracts.Role;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.membership.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

@Service
public class ChildService {
    public static final String NOTICE_VERSION = "2026-10-31.v1";
    private final JdbcTemplate jdbc;
    private final MemberGuard guard;
    private final ChildAccessPolicy access;
    private final AuditTrail audit;
    private final IdempotencyService idempotency;
    private final JsonMapper mapper;
    private final Clock clock;

    public ChildService(
            JdbcTemplate jdbc,
            MemberGuard guard,
            ChildAccessPolicy access,
            AuditTrail audit,
            IdempotencyService idempotency,
            JsonMapper mapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.access = access;
        this.audit = audit;
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public StoredResponse create(ActorPrincipal actor, CreateChildRequest body, String key) {
        StoredResponse result =
                idempotency.execute(
                        actor,
                        "POST",
                        "/api/v1/children",
                        key,
                        body,
                        () -> {
                            guard.lockDomain();
                            guard.require(actor, Role.GUARDIAN);
                        },
                        () -> {
                            UUID id = UUID.randomUUID();
                            var now = Timestamp.from(clock.instant());
                            jdbc.update(
                                    "insert into"
                                        + " care.child(id,guardian_id,pseudonym,profile,profile_version,created_at,updated_at)"
                                        + " values(?,?,?,?::jsonb,1,?,?)",
                                    id,
                                    UUID.fromString(actor.actorId()),
                                    pseudonym(body.pseudonym()),
                                    mapper.writeValueAsString(body.profile()),
                                    now,
                                    now);
                            audit.record(
                                    UUID.fromString(actor.actorId()),
                                    "CHILD_CREATED",
                                    id,
                                    null,
                                    Map.of(),
                                    Map.of(
                                            "guardianRelationshipConfirmed",
                                            true,
                                            "profileVersion",
                                            1));
                            return StoredResponse.json(mapper, 201, profile(id));
                        });
        // Creation cannot know the resource ID until the first execution/replay is resolved.
        // Validate the returned resource before exposing even an encrypted stored response.
        String resolvedChild = mapper.readTree(result.body()).path("childId").asText();
        access.check(actor, resolvedChild, VIEW_PROFILE);
        return result;
    }

    @Transactional
    public Map<String, Object> list(ActorPrincipal actor, int limit, UUID cursor) {
        guard.lockDomain();
        var account = guard.require(actor, Role.GUARDIAN, Role.THERAPIST);
        if (limit < 1 || limit > 100) throw new ApiException(ErrorCode.INVALID_SCHEMA);
        var rows =
                jdbc.query(
                        """
select c.id,c.pseudonym,c.profile_version from care.child c join identity.member g on g.id=c.guardian_id
where c.deleted_at is null and g.status='ACTIVE' and g.role='GUARDIAN'
  and (?::uuid is null or c.id>?::uuid) and
  (c.guardian_id=? or (? and exists(select 1 from care.child_therapist t where t.child_id=c.id and t.therapist_id=? and t.state='ACTIVE')
  and exists(select 1 from care.consent co where co.child_id=c.id and co.scope='CORE_PROFILE_LEARNING' and co.granted)))
order by c.id limit ?
""",
                        (rs, row) ->
                                Map.<String, Object>of(
                                        "childId",
                                        rs.getObject(1, UUID.class),
                                        "pseudonym",
                                        rs.getString(2),
                                        "profileVersion",
                                        rs.getLong(3),
                                        "capabilities",
                                        ChildAccessPolicy.capabilities(account.role())),
                        cursor,
                        cursor,
                        account.id(),
                        account.role() == Role.THERAPIST,
                        account.id(),
                        limit + 1);
        var result = new LinkedHashMap<String, Object>();
        result.put("items", rows.stream().limit(limit).toList());
        result.put("nextCursor", rows.size() > limit ? rows.get(limit - 1).get("childId") : null);
        return result;
    }

    @Transactional
    public Map<String, Object> getProfile(ActorPrincipal actor, UUID child) {
        guard.lockDomain();
        access.check(actor, child.toString(), VIEW_PROFILE);
        return profile(child);
    }

    public StoredResponse updateProfile(
            ActorPrincipal actor, UUID child, UpdateProfileRequest body, String key) {
        return idempotency.execute(
                actor,
                "PUT",
                "/api/v1/children/" + child + "/profile",
                key,
                body,
                () -> {
                    guard.lockDomain();
                    access.check(actor, child.toString(), EDIT_PROFILE);
                },
                () -> {
                    int count =
                            jdbc.update(
                                    "update care.child set"
                                        + " pseudonym=?,profile=?::jsonb,profile_version=profile_version+1,updated_at=?"
                                        + " where id=? and profile_version=?",
                                    pseudonym(body.pseudonym()),
                                    mapper.writeValueAsString(body.profile()),
                                    Timestamp.from(clock.instant()),
                                    child,
                                    body.expectedProfileVersion());
                    if (count != 1) throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
                    audit.record(
                            UUID.fromString(actor.actorId()),
                            "CHILD_PROFILE_UPDATED",
                            child,
                            null,
                            Map.of("profileVersion", body.expectedProfileVersion()),
                            Map.of("profileVersion", body.expectedProfileVersion() + 1));
                    return StoredResponse.json(mapper, 200, profile(child));
                });
    }

    public StoredResponse delete(ActorPrincipal actor, UUID child, String key) {
        return idempotency.execute(
                actor,
                "DELETE",
                "/api/v1/children/" + child,
                key,
                Map.of(),
                () -> {
                    guard.lockDomain();
                    access.check(actor, child.toString(), MANAGE);
                    guard.recent(actor, Role.GUARDIAN);
                },
                () -> {
                    jdbc.update(
                            "update care.child set deleted_at=?,updated_at=? where id=?",
                            Timestamp.from(clock.instant()),
                            Timestamp.from(clock.instant()),
                            child);
                    revokeConnections(child);
                    audit.record(
                            UUID.fromString(actor.actorId()),
                            "CHILD_DELETED",
                            child,
                            null,
                            Map.of("deleted", false),
                            Map.of("deleted", true));
                    return StoredResponse.noContent();
                });
    }

    @Transactional
    public Map<String, Object> consents(ActorPrincipal actor, UUID child) {
        guard.lockDomain();
        access.check(actor, child.toString(), MANAGE);
        return Map.of(
                "items",
                jdbc.query(
                        "select * from care.consent where child_id=? order by scope",
                        (rs, row) -> consentRow(rs),
                        child));
    }

    public StoredResponse changeConsent(
            ActorPrincipal actor, UUID child, ConsentChangeRequest body, String key) {
        return idempotency.execute(
                actor,
                "POST",
                "/api/v1/children/" + child + "/consents",
                key,
                body,
                () -> {
                    guard.lockDomain();
                    access.check(actor, child.toString(), MANAGE);
                    var account = guard.recent(actor, Role.GUARDIAN);
                    if (!body.guardianVerificationRef().equals(account.verificationRef()))
                        throw new ApiException(ErrorCode.REAUTH_REQUIRED);
                },
                () -> {
                    if (body.granted() && !NOTICE_VERSION.equals(body.noticeVersion()))
                        throw new ApiException(ErrorCode.CONSENT_NOTICE_CHANGED);
                    var previous =
                            jdbc.query(
                                    "select granted,revision from care.consent where child_id=? and"
                                            + " scope=?",
                                    (rs, row) ->
                                            Map.of(
                                                    "granted",
                                                    rs.getBoolean(1),
                                                    "revision",
                                                    rs.getLong(2)),
                                    child,
                                    body.scope().name());
                    jdbc.update(
                            """
insert into care.consent (child_id,scope,id,granted,notice_version,verification_ref,recorded_at,withdrawn_at,revision)
values(?,?,?,?,?,?,?,?,1) on conflict(child_id,scope) do update set
granted=excluded.granted,notice_version=excluded.notice_version,verification_ref=excluded.verification_ref,
recorded_at=excluded.recorded_at,withdrawn_at=excluded.withdrawn_at,revision=care.consent.revision+1
""",
                            child,
                            body.scope().name(),
                            UUID.randomUUID(),
                            body.granted(),
                            body.noticeVersion(),
                            body.guardianVerificationRef(),
                            Timestamp.from(clock.instant()),
                            body.granted() ? null : Timestamp.from(clock.instant()));
                    if (!body.granted() && body.scope() == ConsentScope.CORE_PROFILE_LEARNING)
                        revokeConnections(child);
                    var result =
                            jdbc.query(
                                            "select * from care.consent where child_id=? and"
                                                    + " scope=?",
                                            (rs, row) -> consentRow(rs),
                                            child,
                                            body.scope().name())
                                    .getFirst();
                    audit.record(
                            UUID.fromString(actor.actorId()),
                            "CONSENT_CHANGED",
                            child,
                            null,
                            previous.isEmpty() ? Map.of("granted", false) : previous.getFirst(),
                            Map.of(
                                    "scope",
                                    body.scope(),
                                    "granted",
                                    body.granted(),
                                    "revision",
                                    result.get("revision")));
                    return StoredResponse.json(mapper, 200, result);
                });
    }

    @Transactional
    public List<Map<String, Object>> notices(ActorPrincipal actor) {
        guard.lockDomain();
        guard.require(actor);
        return Arrays.stream(ConsentScope.values())
                .map(
                        scope ->
                                Map.<String, Object>of(
                                        "scope",
                                        scope,
                                        "noticeVersion",
                                        NOTICE_VERSION,
                                        "purpose",
                                        switch (scope) {
                                            case CORE_PROFILE_LEARNING ->
                                                    "아동 프로필을 저장하고 학습 기능 및 연결된 치료사의 조회에 사용합니다.";
                                            case SPEECH_STT -> "선택한 음성을 외부 전사 서비스에 전달해 텍스트로 변환합니다.";
                                            case CARD_EXTERNAL_PROCESSING ->
                                                    "선택한 입력을 외부 서비스에 전달해 표현 카드 제안을 생성합니다.";
                                            case CAMERA_LOCAL -> "기기 안에서 카메라 기능을 사용합니다.";
                                            case OPTIONAL_RESEARCH_OR_MODEL_TRAINING ->
                                                    "기본 서비스와 별도로 선택한 연구·모델 학습 목적에 사용합니다.";
                                            case LOCAL_DRAFT_RESUME -> "기기에 임시 입력을 보관해 편집을 복원합니다.";
                                        },
                                        "defaultGranted",
                                        false))
                .toList();
    }

    private Map<String, Object> profile(UUID child) {
        return jdbc.query(
                        "select profile::text,profile_version,updated_at from care.child where"
                                + " id=?",
                        (rs, row) -> {
                            var fields = new LinkedHashMap<String, Object>();
                            mapper.readTree(rs.getString(1))
                                    .properties()
                                    .forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
                            fields.put("childId", child);
                            fields.put("profileVersion", rs.getLong(2));
                            fields.put("createdAt", rs.getTimestamp(3).toInstant());
                            return (Map<String, Object>) fields;
                        },
                        child)
                .getFirst();
    }

    private Map<String, Object> consentRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        var v = new LinkedHashMap<String, Object>();
        v.put("consentId", rs.getObject("id", UUID.class));
        v.put("childId", rs.getObject("child_id", UUID.class));
        v.put("scope", rs.getString("scope"));
        v.put("granted", rs.getBoolean("granted"));
        v.put("noticeVersion", rs.getString("notice_version"));
        v.put("guardianVerificationRef", rs.getObject("verification_ref", UUID.class));
        v.put("recordedAt", rs.getTimestamp("recorded_at").toInstant());
        v.put(
                "withdrawnAt",
                rs.getTimestamp("withdrawn_at") == null
                        ? null
                        : rs.getTimestamp("withdrawn_at").toInstant());
        v.put("revision", rs.getLong("revision"));
        return v;
    }

    private String pseudonym(String value) {
        if (value.codePoints().anyMatch(Character::isISOControl)
                || value.codePoints()
                        .allMatch(
                                c ->
                                        Character.isWhitespace(c)
                                                || Character.isSpaceChar(c)
                                                || c == 0xfeff)) {
            throw new ApiException(ErrorCode.INVALID_SCHEMA);
        }
        return value.strip();
    }

    private void revokeConnections(UUID child) {
        jdbc.update(
                "update care.child_therapist set state='REVOKED',revoked_at=? where child_id=?",
                Timestamp.from(clock.instant()),
                child);
        jdbc.update(
                "update care.therapist_invitation set state='CANCELLED',resolved_at=? where"
                        + " child_id=? and state='PENDING'",
                Timestamp.from(clock.instant()),
                child);
    }
}
