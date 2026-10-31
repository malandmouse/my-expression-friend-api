package com.planb.emotionfriend.api.learning;

import static org.assertj.core.api.Assertions.*;
import static com.planb.emotionfriend.api.learning.LearningContracts.*;
import com.planb.emotionfriend.api.auth.AdultJwt;
import com.planb.emotionfriend.api.common.FoundationIntegrationTest;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LearningIntegrationTest.TimeConfiguration.class)
class LearningIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired AdultJwt jwt;
    @Autowired LearningCatalog catalog;
    @Autowired MutableClock clock;
    @Autowired ReplayCipher cipher;
    @Autowired RequestFingerprint fingerprint;
    @LocalServerPort int port;
    private HttpClient client;
    private User guardian,therapist,stranger,admin;
    private UUID child;
    private List<UUID> materials,summaries;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        FoundationIntegrationTest.isolatedPostgres(registry);
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock learningClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-31T03:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private record User(UUID id,UUID session) {}

    @BeforeEach void prepare() {
        clock.now=Instant.parse("2026-10-31T03:00:00Z");
        client=HttpClient.newHttpClient();
        jdbc.execute("truncate care.child,identity.member,identity.auth_rate_bucket cascade");
        jdbc.update("delete from foundation.idempotency_record");
        guardian=user("GUARDIAN");therapist=user("THERAPIST");stranger=user("GUARDIAN");admin=user("ADMIN");
        child=UUID.randomUUID();
        jdbc.update("insert into care.child(id,guardian_id,pseudonym,profile,profile_version,created_at,updated_at) values(?,?,?,'{}',1,?,?)",
                child,guardian.id(),"SYNTHETIC_CHILD",ts(),ts());
        jdbc.update("insert into care.consent(child_id,id,scope,granted,notice_version,verification_ref,recorded_at,revision) values(?,?,'CORE_PROFILE_LEARNING',true,'synthetic',?,?,1)",
                child,UUID.randomUUID(),UUID.randomUUID(),ts());
        UUID invitation=UUID.randomUUID();
        jdbc.update("insert into care.therapist_invitation(id,child_id,guardian_id,therapist_id,target_email,state,created_at,expires_at) values(?,?,?,?,?,'ACCEPTED',?,?)",
                invitation,child,guardian.id(),therapist.id(),therapist.id()+"@example.test",ts(),Timestamp.from(clock.instant().plusSeconds(86400)));
        jdbc.update("insert into care.child_therapist(child_id,therapist_id,invitation_id,state,accepted_at) values(?,?,?,'ACTIVE',?)",child,therapist.id(),invitation,ts());
        materials=new ArrayList<>();summaries=new ArrayList<>();
        for(int slot=1;slot<=5;slot++) {
            UUID material=UUID.randomUUID(); materials.add(material);
            summaries.add(catalog.storeReviewed(child,new LearningCatalog.ReviewedMaterial(material,UUID.randomUUID(),
                    "합성 주제 "+slot,"검수를 통과한 합성 이야기 요약 "+slot,"synthetic-independent-review-"+slot,
                    List.of(new LearningCatalog.ReviewedPath(List.of("a","b","c"),"end",List.of("happy","unsure"),"nara",
                            Map.of("npc_helped",true,"npc_arrived",true))))));
        }
    }
    @AfterEach void close() { client.close(); }

    @Test void summariesAreMinimalAndConfirmationNeverPublishesOrStartsLearning() throws Exception {
        var response=send("GET",base()+"/learning-material-summaries",null,guardian,null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        var item=json(response).path("items").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("summaryId","materialId","subject","summary","status");
        assertThat(json(response).path("items")).hasSize(5);
        String key=key(); var request=decision("CONFIRM",summaries);
        var accepted=send("POST",base()+"/learning-material-confirmations",request,therapist,key);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(send("POST",base()+"/learning-material-confirmations",request,therapist,key).body()).isEqualTo(accepted.body());
        assertThat(json(send("GET",base()+"/learning-material-summaries",null,guardian,null)).path("items")).isEmpty();
        assertThat(json(send("GET",base()+"/learning-material-summaries?status=CONFIRMED",null,guardian,null)).path("items")).hasSize(5);
        assertThat(count("plan")).isZero(); assertThat(count("session")).isZero(); assertThat(count("receipt")).isZero();
        assertCode(send("POST",base()+"/learning-sessions",startBody(),guardian,key()),"MATERIAL_NOT_READY");
    }

    @Test void holdIsVisibleAndCanBeConfirmedAgain() throws Exception {
        confirmAll();
        assertThat(send("POST",base()+"/learning-material-confirmations",decision("HOLD",List.of(summaries.getFirst())),guardian,key()).statusCode()).isEqualTo(200);
        var page=json(send("GET",base()+"/learning-material-summaries",null,guardian,null));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("status").asText()).isEqualTo("ON_HOLD");
        confirmAll();
        assertThat(json(send("GET",base()+"/learning-material-summaries",null,guardian,null)).path("items")).isEmpty();
    }

    @Test void batchIsAllOrNothingForForeignRetiredDuplicateAndMissingIds() throws Exception {
        catalog.retire(child,materials.get(1));
        assertCode(send("POST",base()+"/learning-material-confirmations",decision("CONFIRM",summaries),guardian,key()),"STALE_DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from learning.material where status='CONFIRMED'",Integer.class)).isZero();
        assertCode(send("POST",base()+"/learning-material-confirmations",decision("CONFIRM",List.of(summaries.getFirst(),UUID.randomUUID())),guardian,key()),"RESOURCE_NOT_FOUND");
        assertCode(send("POST",base()+"/learning-material-confirmations",decision("CONFIRM",List.of(summaries.getFirst(),summaries.getFirst())),guardian,key()),"INVALID_SCHEMA");
        assertCode(send("POST",base()+"/learning-material-confirmations",decision("CONFIRM",List.of()),guardian,key()),"INVALID_SCHEMA");
    }

    @Test void summaryPaginationAndAccessAreChildScoped() throws Exception {
        var first=json(send("GET",base()+"/learning-material-summaries?limit=2",null,guardian,null));
        assertThat(first.path("items")).hasSize(2);
        String cursor=first.path("nextCursor").asText();
        var next=json(send("GET",base()+"/learning-material-summaries?limit=2&cursor="+cursor,null,guardian,null));
        assertThat(next.path("items")).hasSize(2);
        assertThat(next.path("items").get(0).path("materialId").asText()).isNotEqualTo(first.path("items").get(1).path("materialId").asText());
        assertCode(send("GET",base()+"/learning-material-summaries?limit=0",null,guardian,null),"INVALID_SCHEMA");
        assertThat(send("GET",base()+"/learning-material-summaries",null,null,null).statusCode()).isEqualTo(401);
        assertThat(send("GET",base()+"/learning-material-summaries",null,stranger,null).statusCode()).isEqualTo(404);
        assertThat(send("GET",base()+"/learning-material-summaries",null,admin,null).statusCode()).isEqualTo(403);
    }

    @Test void planNeedsFiveConfirmedMaterialsAndSeparatePublicationEvidence() throws Exception {
        assertThatThrownBy(()->catalog.publish(child,"Asia/Seoul",materials,"synthetic-publication"))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.CONTENT_NOT_APPROVED));
        confirmAll();
        assertThatThrownBy(()->catalog.publish(child,"Asia/Seoul",materials.subList(0,4),"synthetic-publication")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->catalog.publish(child,"Bad/Zone",materials,"synthetic-publication")).isInstanceOf(ApiException.class);
        UUID plan=catalog.publish(child,"Asia/Seoul",materials,"synthetic-publication");
        assertThat(plan).isNotNull(); assertThat(count("session")).isZero();
        assertThat(send("POST",base()+"/learning-sessions",startBody(),therapist,key()).statusCode()).isEqualTo(403);
    }

    @Test void startAndResumePreserveFivePinsAndNeverAwardStars() throws Exception {
        UUID session=start();
        var resumed=send("POST",base()+"/learning-sessions",startBody(),guardian,key());
        assertThat(resumed.statusCode()).isEqualTo(200);
        assertThat(json(resumed).path("sessionId").asText()).isEqualTo(session.toString());
        assertThat(json(resumed).path("slots")).hasSize(5);
        assertThat(json(resumed).path("stars").asInt()).isZero();
        assertThat(count("receipt")).isZero();
        assertThat(send("POST",base()+"/learning-sessions",Map.of("schemaVersion","0.3.0","deviceId","different-device"),guardian,key()).statusCode()).isEqualTo(409);
        assertThat(send("GET",sessionPath(session),null,therapist,null).statusCode()).isEqualTo(200);
    }

    @Test void fiveValidCompletionsUpdateSessionResultsRecordsAndSummary() throws Exception {
        UUID session=start();
        for(int slot=1;slot<=5;slot++) {
            var command=completion(session,slot);
            var accepted=send("POST",slotPath(session,slot)+"/finalize",command,guardian,key());
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
            assertThat(json(accepted).path("stars").asInt()).isEqualTo(slot);
            assertThat(json(accepted).path("starsAwarded").asInt()).isEqualTo(1);
        }
        var state=json(send("GET",sessionPath(session),null,guardian,null));
        assertThat(state.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(state.path("stars").asInt()).isEqualTo(5);
        assertThat(state.path("completedCount").asInt()).isEqualTo(5);
        assertThat(state.path("worldRevision").asLong()).isEqualTo(5);
        assertThat(count("receipt")).isEqualTo(5);assertThat(count("star_award")).isEqualTo(5);assertThat(count("story_event")).isEqualTo(5);
        assertThat(json(send("GET",base()+"/learning-sessions?status=COMPLETED",null,therapist,null)).path("items")).hasSize(1);
        assertThat(json(send("GET",base()+"/learning-results",null,therapist,null)).path("items")).hasSize(5);
        assertThat(json(send("GET",base()+"/learning-records?limit=2",null,guardian,null)).path("items")).hasSize(2);
        clock.now=clock.now.plusSeconds(1);
        var summary=json(send("GET",base()+"/learning-summary",null,therapist,null));
        assertThat(summary.path("completedLoops").asInt()).isEqualTo(5);
        assertThat(summary.path("starsEarned").asInt()).isEqualTo(5);
        assertThat(summary.path("recognitionCounts")).isEmpty();
        assertThat(summary.path("metricSnapshotHash").asText()).hasSize(64);
        assertCode(send("GET",slotPath(session,6)+"/receipt",null,guardian,null),"INVALID_SCHEMA");
    }

    @Test void concurrentCompletionAndDifferentKeyRetryHaveOnlyOneEffect() throws Exception {
        UUID session=start();var command=completion(session,1);String path=slotPath(session,1)+"/finalize";
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->send("POST",path,command,guardian,key()));
            var second=pool.submit(()->send("POST",path,command,guardian,key()));
            var a=first.get(30,TimeUnit.SECONDS);var b=second.get(30,TimeUnit.SECONDS);
            assertThat(a.statusCode()).as(a.body()).isEqualTo(200);assertThat(b.statusCode()).as(b.body()).isEqualTo(200);
            assertThat(json(a)).isEqualTo(json(b));
        }
        assertThat(count("receipt")).isEqualTo(1);assertThat(count("star_award")).isEqualTo(1);assertThat(count("story_event")).isEqualTo(1);
        var changed=new LinkedHashMap<>(command);changed.put("clientCommitId",key());
        assertCode(send("POST",path,changed,guardian,key()),"IDEMPOTENCY_CONFLICT");
    }

    @Test void sameKeyDifferentBodyConflictsAndGetReceiptRecoversAcceptedSnapshot() throws Exception {
        UUID session=start();var command=completion(session,1);String path=slotPath(session,1)+"/finalize";String key=key();
        var accepted=send("POST",path,command,guardian,key);
        assertThat(send("POST",path,command,guardian,key).body()).isEqualTo(accepted.body());
        var changed=new LinkedHashMap<>(command);changed.put("selectedEmotionId","unsure");
        assertCode(send("POST",path,changed,guardian,key),"IDEMPOTENCY_CONFLICT");
        assertThat(json(send("GET",slotPath(session,1)+"/receipt",null,therapist,null))).isEqualTo(json(accepted));
        assertThat(send("GET",slotPath(session,2)+"/receipt",null,guardian,null).statusCode()).isEqualTo(404);
    }

    @Test void staleRevisionInvalidPathAndForgedProofNeverAward() throws Exception {
        UUID session=start();var command=completion(session,1);String path=slotPath(session,1)+"/finalize";
        var stale=new LinkedHashMap<>(command);stale.put("expectedSessionRevision",4);
        assertCode(send("POST",path,stale,guardian,key()),"STALE_CHECKPOINT");
        stale=new LinkedHashMap<>(command);stale.put("expectedWorldRevision",9);
        assertCode(send("POST",path,stale,guardian,key()),"STALE_WORLD_STATE");
        stale=new LinkedHashMap<>(command);stale.put("selectedChoiceIds",List.of("forged"));
        assertCode(send("POST",path,stale,guardian,key()),"INVALID_BRANCH_PATH");
        stale=new LinkedHashMap<>(command);stale.put("confirmedExpressionRef",UUID.randomUUID());
        assertCode(send("POST",path,stale,guardian,key()),"STALE_DRAFT");
        assertThat(count("receipt")).isZero();assertThat(count("star_award")).isZero();
    }

    @Test void expressionMustBeExplicitAndNeverCountsAsCompletionOrPersistsRawText() throws Exception {
        UUID session=start();String raw="SYNTHETIC_RAW_TEXT_NEVER_PERSIST";
        var body=new LinkedHashMap<String,Object>(Map.of("text",raw,"draftVersion",1,"inputSource","TYPED","childConfirmed",false));
        assertCode(send("POST",slotPath(session,1)+"/confirmed-expressions",body,guardian,key()),"INVALID_SCHEMA");
        body.put("childConfirmed",true);String key=key();
        var accepted=send("POST",slotPath(session,1)+"/confirmed-expressions",body,guardian,key);
        assertThat(accepted.statusCode()).isEqualTo(201);assertThat(accepted.body()).doesNotContain(raw);
        assertThat(send("POST",slotPath(session,1)+"/confirmed-expressions",body,guardian,key).body()).isEqualTo(accepted.body());
        assertThat(count("receipt")).isZero();
        for(var row:jdbc.queryForList("select actor_scope,method,path,key_digest,status_code,content_type,encrypted_body from foundation.idempotency_record")) {
            String scope=fingerprint.scope((String)row.get("actor_scope"),(String)row.get("method"),
                    (String)row.get("path"),(String)row.get("key_digest"));
            byte[] replay=cipher.decrypt((byte[])row.get("encrypted_body"),
                    scope+":"+row.get("status_code")+":"+row.get("content_type"));
            assertThat(new String(replay,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(raw);
        }
        assertThat(jdbc.queryForObject("select string_agg(row_to_json(s)::text,'') from learning.session s",String.class)).doesNotContain(raw);
        assertThat(jdbc.queryForObject("select string_agg(row_to_json(a)::text,'') from identity.audit_event a",String.class)).doesNotContain(raw);
    }

    @Test void midnightRejectsNewCompletionButKeepsPastReceiptAndReadDoesNotUnlockDay() throws Exception {
        clock.now=Instant.parse("2026-10-31T14:59:55Z");
        UUID session=start();var first=completion(session,1);String key=key();
        var accepted=send("POST",slotPath(session,1)+"/finalize",first,guardian,key);
        var next=completion(session,2);
        clock.now=clock.now.plusSeconds(6);
        var expired=send("POST",slotPath(session,2)+"/finalize",next,guardian,key());
        assertCode(expired,"REWARD_WINDOW_EXPIRED");
        assertThat(json(expired).path("details").path("nextAction").asText()).isEqualTo("START_REVIEW");
        assertThat(send("POST",slotPath(session,1)+"/finalize",first,guardian,key).body()).isEqualTo(accepted.body());
        assertThat(json(send("GET",slotPath(session,1)+"/receipt",null,guardian,null))).isEqualTo(json(accepted));
        assertThat(count("session")).isEqualTo(1);assertThat(count("receipt")).isEqualTo(1);
    }

    @Test void rollbackWhenStarInsertFailsLeavesNoPartialReceiptWorldSessionOrReplay() throws Exception {
        UUID session=start();var command=completion(session,1);String key=key();
        jdbc.execute("alter table learning.star_award add constraint synthetic_reject_award check(amount=0)");
        try { assertThat(send("POST",slotPath(session,1)+"/finalize",command,guardian,key).statusCode()).isEqualTo(500); }
        finally { jdbc.execute("alter table learning.star_award drop constraint synthetic_reject_award"); }
        assertThat(count("receipt")).isZero();assertThat(count("star_award")).isZero();assertThat(count("story_event")).isZero();
        assertThat(jdbc.queryForObject("select revision from learning.session where id=?",Long.class,session)).isEqualTo(1);
        assertThat(send("POST",slotPath(session,1)+"/finalize",command,guardian,key).statusCode()).isEqualTo(200);
    }

    @Test void consentDisconnectDeletionAndSuspensionBlockReadAndReplay() throws Exception {
        UUID session=start();var command=completion(session,1);String key=key();String path=slotPath(session,1)+"/finalize";
        assertThat(send("POST",path,command,guardian,key).statusCode()).isEqualTo(200);
        jdbc.update("update care.child_therapist set state='REVOKED' where child_id=?",child);
        assertThat(send("GET",slotPath(session,1)+"/receipt",null,therapist,null).statusCode()).isEqualTo(404);
        jdbc.update("update care.consent set granted=false where child_id=?",child);
        assertCode(send("POST",path,command,guardian,key),"CONSENT_REQUIRED");
        assertCode(send("GET",slotPath(session,1)+"/receipt",null,guardian,null),"CONSENT_REQUIRED");
        jdbc.update("update care.consent set granted=true where child_id=?",child);
        jdbc.update("update care.child set deleted_at=? where id=?",ts(),child);
        assertThat(send("POST",path,command,guardian,key).statusCode()).isEqualTo(404);
        jdbc.update("update care.child set deleted_at=null where id=?",child);
        jdbc.update("update identity.member set status='SUSPENDED' where id=?",guardian.id());
        assertThat(send("POST",path,command,guardian,key).statusCode()).isEqualTo(401);
        assertThat(count("receipt")).isEqualTo(1);
    }

    @Test void holdRetirementOrHashTamperingBlocksNewCompletion() throws Exception {
        UUID session=start();var command=completion(session,1);String path=slotPath(session,1)+"/finalize";
        send("POST",base()+"/learning-material-confirmations",decision("HOLD",List.of(summaries.getFirst())),therapist,key());
        assertCode(send("POST",path,command,guardian,key()),"MATERIAL_NOT_READY");
        send("POST",base()+"/learning-material-confirmations",decision("CONFIRM",List.of(summaries.getFirst())),guardian,key());
        jdbc.update("update learning.material set content_hash=? where id=?","0".repeat(64),materials.getFirst());
        assertCode(send("POST",path,command,guardian,key()),"CONTENT_HASH_MISMATCH");
        catalog.retire(child,materials.getFirst());
        assertCode(send("POST",path,command,guardian,key()),"MATERIAL_NOT_READY");
        assertThat(count("receipt")).isZero();
    }

    @Test void openApiAdvertisesActualOperationsAndDoesNotExposePipelinePort() throws Exception {
        var api=json(send("GET","/v3/api-docs",null,null,null));
        assertThat(api.path("paths").has("/api/v1/children/{childId}/learning-material-confirmations")).isTrue();
        assertThat(api.path("paths").has("/api/v1/learning-sessions/{sessionId}/slots/{slot}/finalize")).isTrue();
        assertThat(api.path("paths").toString()).doesNotContain("storeReviewed","/internal/");
        assertThat(send("POST","/internal/v1/materials/generate",Map.of(),guardian,key()).statusCode()).isEqualTo(403);
    }

    @Test void retiredSummaryAndDisconnectedTherapistCannotReplayPastConfirmation() throws Exception {
        var body=decision("CONFIRM",List.of(summaries.getFirst()));String key=key();
        assertThat(send("POST",base()+"/learning-material-confirmations",body,therapist,key).statusCode()).isEqualTo(200);
        jdbc.update("update care.child_therapist set state='REVOKED' where child_id=?",child);
        assertCode(send("POST",base()+"/learning-material-confirmations",body,therapist,key),"RESOURCE_NOT_FOUND");
        String ownerKey=key();
        assertThat(send("POST",base()+"/learning-material-confirmations",body,guardian,ownerKey).statusCode()).isEqualTo(200);
        catalog.retire(child,materials.getFirst());
        assertCode(send("POST",base()+"/learning-material-confirmations",body,guardian,ownerKey),"STALE_DRAFT");
    }

    @Test void newerDraftInvalidatesOldProofAndExpiredProofCannotFinalize() throws Exception {
        UUID session=start();var old=completion(session,1);String path=slotPath(session,1)+"/finalize";
        var newer=send("POST",slotPath(session,1)+"/confirmed-expressions",
                Map.of("text","합성 새 초안","draftVersion",2,"inputSource","TYPED","childConfirmed",true),guardian,key());
        assertThat(newer.statusCode()).isEqualTo(201);
        assertCode(send("POST",path,old,guardian,key()),"STALE_DRAFT");
        var command=new LinkedHashMap<>(old);command.put("confirmedExpressionRef",json(newer).path("confirmedExpressionRef").asText());
        command.put("draftVersion",2);
        clock.now=clock.now.plusSeconds(901);
        assertCode(send("POST",path,command,guardian,key()),"STALE_DRAFT");
        assertThat(count("receipt")).isZero();
    }

    @Test void expressionRollbackPreservesPreviousProofAndAllowsSameDraftRetry() throws Exception {
        UUID session=start();var old=completion(session,1);
        var body=Map.of("text","합성 새 초안","draftVersion",2,"inputSource","TYPED","childConfirmed",true);
        jdbc.execute("alter table foundation.idempotency_record add constraint synthetic_learning_reject_expression check(path not like '%confirmed-expressions') not valid");
        try { assertThat(send("POST",slotPath(session,1)+"/confirmed-expressions",body,guardian,key()).statusCode()).isEqualTo(500); }
        finally { jdbc.execute("alter table foundation.idempotency_record drop constraint synthetic_learning_reject_expression"); }
        assertThat(send("POST",slotPath(session,1)+"/finalize",old,guardian,key()).statusCode()).isEqualTo(200);
    }

    @Test void aggregatesUseInclusiveStartExclusiveEndAndRejectInvalidRanges() throws Exception {
        UUID session=start();var command=completion(session,1);
        assertThat(send("POST",slotPath(session,1)+"/finalize",command,guardian,key()).statusCode()).isEqualTo(200);
        String instant=clock.instant().toString();
        String previous=clock.instant().minusSeconds(1).toString();
        String following=clock.instant().plusSeconds(1).toString();
        assertThat(json(send("GET",base()+"/learning-summary?from="+previous+"&to="+instant,null,guardian,null)).path("completedLoops").asInt()).isZero();
        assertThat(json(send("GET",base()+"/learning-summary?from="+instant+"&to="+following,null,guardian,null)).path("completedLoops").asInt()).isEqualTo(1);
        assertCode(send("GET",base()+"/learning-summary?from="+instant+"&to="+instant,null,guardian,null),"INVALID_SCHEMA");
        assertCode(send("GET",base()+"/learning-summary?from=2024-01-01T00:00:00Z&to="+instant,null,guardian,null),"INVALID_SCHEMA");
    }

    private UUID start() throws Exception {
        confirmAll();catalog.publish(child,"Asia/Seoul",materials,"synthetic-publication-evidence");
        var response=send("POST",base()+"/learning-sessions",startBody(),guardian,key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json(response).path("sessionId").asText());
    }
    private void confirmAll() throws Exception {
        var response=send("POST",base()+"/learning-material-confirmations",decision("CONFIRM",summaries),guardian,key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
    private Map<String,Object> completion(UUID session,int slot) throws Exception {
        var expression=send("POST",slotPath(session,slot)+"/confirmed-expressions",
                Map.of("text","합성 직접 입력","draftVersion",1,"inputSource","TYPED","childConfirmed",true),guardian,key());
        assertThat(expression.statusCode()).as(expression.body()).isEqualTo(201);
        var command=new LinkedHashMap<String,Object>();
        command.put("confirmedExpressionRef",json(expression).path("confirmedExpressionRef").asText());
        command.put("draftVersion",1);command.put("selectedChoiceIds",List.of("a","b","c"));
        command.put("terminalSceneId","end");command.put("selectedEmotionId","happy");command.put("expressionChoice","ORIGINAL");
        command.put("expectedSessionRevision",slot);command.put("expectedWorldRevision",slot-1);command.put("clientCommitId",key());
        return command;
    }
    private Map<String,Object> startBody() { return Map.of("schemaVersion","0.3.0","deviceId","synthetic-device"); }
    private Map<String,Object> decision(String decision,List<UUID> ids) { return Map.of("summaryIds",ids,"decision",decision); }
    private User user(String role) {
        UUID id=UUID.randomUUID(),session=UUID.randomUUID();
        jdbc.update("insert into identity.member(id,email,display_name,password_hash,requested_role,role,status,email_verified_at,created_at,updated_at) values(?,?,?,'unused-synthetic-hash',?,?,'ACTIVE',?,?,?)",
                id,id+"@example.test","합성 사용자",role.equals("ADMIN")?null:role,role,ts(),ts(),ts());
        jdbc.update("insert into identity.auth_session(id,member_id,created_at,expires_at) values(?,?,?,?)",session,id,ts(),Timestamp.from(clock.instant().plusSeconds(86400)));
        return new User(id,session);
    }
    private HttpResponse<String> send(String method,String path,Object body,User user,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(user!=null) request.header("Authorization","Bearer "+jwt.issue(user.id(),user.session(),clock.instant().plusSeconds(3600)).accessToken());
        if(key!=null) request.header("Idempotency-Key",key);
        if(body!=null) request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from learning."+table,Integer.class); }
    private String base() { return "/api/v1/children/"+child; }
    private String sessionPath(UUID session) { return "/api/v1/learning-sessions/"+session; }
    private String slotPath(UUID session,int slot) { return sessionPath(session)+"/slots/"+slot; }
    private String key() { return UUID.randomUUID().toString(); }
    private Timestamp ts() { return Timestamp.from(clock.instant()); }
    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private void assertCode(HttpResponse<String> response,String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(ErrorCode.valueOf(code).status());
        assertThat(json(response).path("code").asText()).isEqualTo(code);
    }
}
