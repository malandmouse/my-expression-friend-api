package com.planb.emotionfriend.api.learning;

import static org.assertj.core.api.Assertions.*;
import com.planb.emotionfriend.api.auth.AdultJwt;
import com.planb.emotionfriend.api.common.FoundationIntegrationTest;
import com.planb.emotionfriend.api.common.error.*;
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

/** Real loopback HTTP/PostgreSQL; every identity, approval, byte and device is synthetic. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"emotion-friend.core.child-use-enabled=true","emotion-friend.core.lease-seconds=30"})
@Import(CoreRuntimeIntegrationTest.SyntheticConfiguration.class)
class CoreRuntimeIntegrationTest {
    @Autowired JdbcTemplate db;@Autowired JsonMapper json;@Autowired AdultJwt jwt;
    @Autowired CoreManuscriptStore manuscripts;@Autowired CorePublicationService content;
    @Autowired TestClock clock;@Autowired SyntheticAuthority authority;
    @Autowired SyntheticMedia media;
    @LocalServerPort int port;
    HttpClient client;UUID child;User guardian,reviewer,publisher,stranger;CoreSyntheticFixture fixture;
    record User(UUID id,UUID session) {}
    record Day(UUID plan,List<UUID> materials,List<UUID> validations,List<UUID> publications,List<UUID> uses) {}
    static class TestClock extends Clock {
        volatile Instant now=Instant.parse("2026-11-02T03:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
    }
    static class SyntheticAuthority implements ContentAuthority {
        final Set<UUID> reviewers=new HashSet<>(),publishers=new HashSet<>();UUID child;
        public void require(com.planb.emotionfriend.api.common.security.ActorPrincipal actor,UUID target,Capability capability) {
            var set=capability==Capability.CONTENT_REVIEW?reviewers:publishers;
            if(actor==null || !set.contains(UUID.fromString(actor.actorId())) || target!=null && !target.equals(child))throw new ApiException(ErrorCode.POLICY_REVIEW_FORBIDDEN);
        }
    }
    static class SyntheticMedia implements CoreMediaDelivery {
        final JdbcTemplate db;boolean corrupt;
        SyntheticMedia(JdbcTemplate db){this.db=db;}
        public byte[] read(UUID id) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return corrupt?new byte[]{1,2,3}:db.queryForObject("select bytes from learning.core_artifact where id=?",byte[].class,id);
        }
    }
    @TestConfiguration static class SyntheticConfiguration {
        @Bean @Primary TestClock testClock(){return new TestClock();}
        @Bean @Primary SyntheticAuthority syntheticAuthority(){return new SyntheticAuthority();}
        @Bean @Primary SyntheticMedia syntheticBytes(JdbcTemplate db) {return new SyntheticMedia(db);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){FoundationIntegrationTest.isolatedPostgres(r);}
    @BeforeEach void setup() throws Exception {
        clock.now=Instant.parse("2026-11-02T03:00:00Z");client=HttpClient.newHttpClient();
        db.execute("truncate care.child,identity.member,identity.auth_rate_bucket cascade");db.update("delete from foundation.idempotency_record");
        // Shared artifacts are immutable global versions and are safely reused across test cases.
        guardian=user("GUARDIAN");reviewer=user("THERAPIST");publisher=user("ADMIN");stranger=user("GUARDIAN");child=UUID.randomUUID();
        db.update("insert into care.child(id,guardian_id,pseudonym,profile,profile_version,created_at,updated_at) values(?,?,'SYNTHETIC','{}',1,?,?)",child,guardian.id(),ts(),ts());
        db.update("insert into care.consent(child_id,id,scope,granted,notice_version,verification_ref,recorded_at,revision) values(?,?,'CORE_PROFILE_LEARNING',true,'synthetic-only',?,?,1)",child,UUID.randomUUID(),UUID.randomUUID(),ts());
        authority.reviewers.clear();authority.publishers.clear();authority.reviewers.add(reviewer.id());authority.publishers.add(publisher.id());authority.child=child;
        fixture=new CoreSyntheticFixture();fixture.artifacts.values().forEach(content::artifact);
        media.corrupt=false;
    }
    @AfterEach void close(){client.close();}

    @Test void approvalPublicationAuthenticationFiveSlotsReconnectAndDayTwoKeepOneLedgerAndPastReceipt() throws Exception {
        Day day=day(1);String pre=connect();var persistent=accepted(send("POST","/api/v1/child-sessions/persist",device(),pre,key()),200);
        var start=start(day,pre);String token=token(start.path("runtimeCredential"));UUID session=id(start.path("session"),"sessionId");
        JsonNode first=null;
        for(int slot=1;slot<=5;slot++){var receipt=accepted(send("POST",finalizePath(session,slot),command(session,slot,token),token,key()),200);if(slot==1)first=receipt;}
        assertThat(count("receipt")).isEqualTo(5);assertThat(count("star_award")).isEqualTo(5);assertThat(count("story_event")).isEqualTo(5);
        assertThat(db.queryForObject("select revision from learning.child_world where child_id=?",Long.class,child)).isEqualTo(5);
        String refresh=persistent.path("refreshToken").asText();clock.now=clock.now.plus(Duration.ofDays(1));
        String resumed=refresh(refresh);var beforeHome=accepted(send("GET",homePath(),null,resumed,null),200);
        assertThat(beforeHome.path("plans").size()).isEqualTo(1);
        Day second=day(2);assertCode(send("POST","/api/v1/daily-plans/"+second.plan()+"/sessions",startBody(),resumed,key()),"MATERIAL_NOT_READY");
        accepted(send("POST",entryPath(),entry(),resumed,key()),200);
        var start2=start(second,resumed);String token2=token(start2.path("runtimeCredential"));UUID session2=id(start2.path("session"),"sessionId");
        assertThat(start2.path("session").path("worldRevision").asLong()).isEqualTo(5);
        accepted(send("POST",finalizePath(session2,1),command(session2,1,token2),token2,key()),200);
        assertThat(db.queryForObject("select revision from learning.child_world where child_id=?",Long.class,child)).isEqualTo(6);
        // Reissue plan-scoped credential using a pre-plan credential; old receipt retains revision 1.
        var oldStart=start(day,resumed);String old=token(oldStart.path("runtimeCredential"));
        assertThat(accepted(send("GET",receiptPath(session,1),null,old,null),200)).isEqualTo(first);
        var recovered=accepted(send("GET","/api/v1/sessions/"+session2,null,token2,null),200);
        assertThat(recovered.path("checkpoint").path("slot").asInt()).isEqualTo(2);
        assertThat(recovered.path("pin").path("entryWorldRevision").asLong()).isEqualTo(5);
        assertThat(db.queryForObject("select count(*) from identity.audit_event where action='LEARNING_SLOT_COMPLETED'",Integer.class)).isEqualTo(6);
        export("two-day.json",Map.of("firstReceipt",first,"recovered",recovered,"rewards",6,"syntheticMedia",true));
    }
    @Test void confirmationAloneCannotPublishAndRoleAloneCannotReviewOrPublish() throws Exception {
        var d=draft(1,1);UUID summary=manuscripts.store(child,d);UUID v=validate(d);String hash=content.hash(v);
        accepted(send("POST",base()+"/learning-material-confirmations",Map.of("summaryIds",List.of(summary),"decision","CONFIRM"),adult(guardian),key()),200);
        assertCode(send("POST",validationPath(v,"full-review"),evidence(hash),adult(publisher),null),"POLICY_REVIEW_FORBIDDEN");
        UUID review=evidenceId(send("POST",validationPath(v,"full-review"),evidence(hash),adult(reviewer),null));
        assertCode(send("POST",validationPath(v,"child-use"),useBody(hash),adult(reviewer),null),"RESOURCE_NOT_FOUND");
        assertCode(send("POST",validationPath(v,"publish"),publishBody(hash,review,UUID.randomUUID()),adult(guardian),null),"POLICY_REVIEW_FORBIDDEN");
        assertCode(send("POST",validationPath(v,"publish"),publishBody(hash,review,UUID.randomUUID()),adult(publisher),null),"CONTENT_NOT_APPROVED");
        assertThat(count("plan")).isZero();assertThat(count("session")).isZero();
        assertThat(db.queryForObject("select report->>'dependenciesStatus' from learning.core_manuscript_validation where material_id=?",String.class,d.materialId())).isEqualTo("NOT_CHECKED");
    }
    @Test void staleHashMissingAssetsAndImmutableEvidenceAreRejected() throws Exception {
        var d=draft(1,1);manuscripts.store(child,d);
        var ids=new ArrayList<>(fixture.artifacts.values().stream().map(CoreDependencyValidator.Artifact::id).toList());ids.removeFirst();
        assertThatThrownBy(()->content.validate(d.materialId(),ids,fixture.cues)).isInstanceOf(ApiException.class);
        UUID v=validate(d);assertCode(send("POST",validationPath(v,"full-review"),evidence("0".repeat(64)),adult(reviewer),null),"CONTENT_HASH_MISMATCH");
        var a=fixture.artifacts.values().iterator().next();
        assertThatThrownBy(()->content.artifact(new CoreDependencyValidator.Artifact(a.id(),a.kind(),a.sourceId(),new byte[]{1},a.bytesHash()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->db.update("update learning.core_dependency_validation set manifest='{}' where id=?",v)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("delete from learning.core_artifact where id=?",a.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("core_dependency_validation")).isEqualTo(1);
    }
    @Test void deviceGrantRequiresRecentGuardianAndBindsDeviceAndOneTimeExchange() throws Exception {
        assertCode(send("POST",base()+"/device-grants",device(),adult(stranger),key()),"RESOURCE_NOT_FOUND");
        db.update("update identity.auth_session set reauthenticated_at=null where id=?",guardian.session());
        assertCode(send("POST",base()+"/device-grants",device(),adult(guardian),key()),"REAUTH_REQUIRED");
        db.update("update identity.auth_session set reauthenticated_at=? where id=?",ts(),guardian.session());
        String grantKey=key();var grant=accepted(send("POST",base()+"/device-grants",device(),adult(guardian),grantKey),201);
        var wrong=Map.of("deviceId","other","oneTimeCode",grant.path("oneTimeCode").asText());assertCode(send("POST","/api/v1/child-sessions/exchange",wrong,null,key()),"AUTH_REQUIRED");
        var exchange=Map.of("deviceId","synthetic-device","oneTimeCode",grant.path("oneTimeCode").asText());String exchangeKey=key();
        var one=accepted(send("POST","/api/v1/child-sessions/exchange",exchange,null,exchangeKey),200);
        assertThat(accepted(send("POST","/api/v1/child-sessions/exchange",exchange,null,exchangeKey),200)).isEqualTo(one);
        assertCode(send("POST","/api/v1/child-sessions/exchange",exchange,null,key()),"AUTH_REQUIRED");
        assertCode(send("POST",base()+"/device-grants",device(),adult(guardian),grantKey),"AUTH_REQUIRED");
    }
    @Test void runtimeAuthenticationIsSeparatedAndOtherChildAndPlanCannotBeRead() throws Exception {
        Day d=day(1);String pre=connect();var started=start(d,pre);String runtime=token(started.path("runtimeCredential"));UUID s=id(started.path("session"),"sessionId");
        assertCode(send("GET","/api/v1/me",null,runtime,null),"AUTH_REQUIRED");
        assertCode(send("GET","/api/v1/sessions/"+s,null,adult(guardian),null),"AUTH_REQUIRED");
        assertCode(send("GET","/api/v1/children/"+UUID.randomUUID()+"/learning-home",null,pre,null),"RESOURCE_NOT_FOUND");
        assertCode(send("GET","/api/v1/sessions/"+s,null,pre,null),"CHILD_ACCESS_DENIED");
        assertCode(send("POST","/api/v1/daily-plans/"+UUID.randomUUID()+"/sessions",startBody(),runtime,key()),"CHILD_ACCESS_DENIED");
    }
    @Test void refreshRotationHasFixedExpirySafeImmediateReplayAndReuseRevokesFamily() throws Exception {
        String pre=connect();var persistent=accepted(send("POST","/api/v1/child-sessions/persist",device(),pre,key()),200);
        var request=Map.of("refreshToken",persistent.path("refreshToken").asText(),"deviceId","synthetic-device");String k=key();
        var next=accepted(send("POST","/api/v1/child-sessions/refresh",request,null,k),200);
        assertThat(next.path("refreshExpiresAt")).isEqualTo(persistent.path("refreshExpiresAt"));
        assertThat(accepted(send("POST","/api/v1/child-sessions/refresh",request,null,k),200)).isEqualTo(next);
        assertCode(send("POST","/api/v1/child-sessions/refresh",request,null,key()),"AUTH_REQUIRED");
        assertCode(send("GET",homePath(),null,token(next.path("runtimeCredential")),null),"AUTH_REQUIRED");
        assertThat(db.queryForObject("select revoked from learning.runtime_device where child_id=?",Boolean.class,child)).isTrue();
    }
    @Test void expiredRuntimeAndGrantAndRefreshCannotBeUsed() throws Exception {
        var grant=accepted(send("POST",base()+"/device-grants",device(),adult(guardian),key()),201);clock.now=clock.now.plusSeconds(301);
        assertCode(send("POST","/api/v1/child-sessions/exchange",Map.of("deviceId","synthetic-device","oneTimeCode",grant.path("oneTimeCode").asText()),null,key()),"AUTH_REQUIRED");
        String pre=connect();var p=accepted(send("POST","/api/v1/child-sessions/persist",device(),pre,key()),200);
        clock.now=clock.now.plusSeconds(601);assertCode(send("GET",homePath(),null,pre,null),"TOKEN_EXPIRED");
        clock.now=clock.now.plus(Duration.ofDays(30));assertCode(send("POST","/api/v1/child-sessions/refresh",Map.of("refreshToken",p.path("refreshToken").asText(),"deviceId","synthetic-device"),null,key()),"AUTH_REQUIRED");
    }
    @Test void consentWithdrawalReconsentAndGuardianSuspensionNeverResurrectCredentials() throws Exception {
        String pre=connect();var p=accepted(send("POST","/api/v1/child-sessions/persist",device(),pre,key()),200);
        db.update("update care.consent set granted=false,revision=revision+1 where child_id=?",child);assertCode(send("GET",homePath(),null,pre,null),"AUTH_REQUIRED");
        db.update("update care.consent set granted=true,revision=revision+1 where child_id=?",child);assertCode(send("GET",homePath(),null,pre,null),"AUTH_REQUIRED");
        assertCode(send("POST","/api/v1/child-sessions/refresh",Map.of("refreshToken",p.path("refreshToken").asText(),"deviceId","synthetic-device"),null,key()),"AUTH_REQUIRED");
        String approvedAgain=connect();accepted(send("GET",homePath(),null,approvedAgain,null),200);
        db.update("update identity.member set status='SUSPENDED' where id=?",guardian.id());assertCode(send("GET",homePath(),null,approvedAgain,null),"AUTH_REQUIRED");
        db.update("update identity.member set status='ACTIVE' where id=?",guardian.id());assertCode(send("GET",homePath(),null,approvedAgain,null),"AUTH_REQUIRED");
    }
    @Test void checkpointRejectsWrongPinAttemptPathRevisionAndExpiredProof() throws Exception {
        var start=start(day(1),connect());String t=token(start.path("runtimeCredential"));UUID s=id(start.path("session"),"sessionId");
        var cp=checkpointRequest(s,t);var wrong=new LinkedHashMap<>(cp);wrong.put("manifestHash","0".repeat(64));assertCode(send("PUT",checkpointPath(s),wrong,t,key()),"CONTENT_HASH_MISMATCH");
        wrong=new LinkedHashMap<>(cp);wrong.put("attemptId",UUID.randomUUID());assertCode(send("PUT",checkpointPath(s),wrong,t,key()),"STALE_CHECKPOINT");
        wrong=new LinkedHashMap<>(cp);wrong.put("selectedChoiceIds",List.of("missing"));assertCode(send("PUT",checkpointPath(s),wrong,t,key()),"INVALID_BRANCH_PATH");
        accepted(send("PUT",checkpointPath(s),cp,t,key()),200);assertCode(send("PUT",checkpointPath(s),cp,t,key()),"STALE_CHECKPOINT");
        var command=command(s,1,t);clock.now=clock.now.plusSeconds(901);
        String renewed=token(accepted(send("POST","/api/v1/child-sessions/renew",device(),connect(),key()),200));
        // New start supplies a scoped credential; expired proof requires explicit reconfirmation.
        var restart=start(new Day(id(start.path("session"),"planId"),List.of(),List.of(),List.of(),List.of()),renewed);String scoped=token(restart.path("runtimeCredential"));
        assertCode(send("POST",finalizePath(s,1),command,scoped,key()),"STALE_DRAFT");
    }
    @Test void duplicateFinalizeAndResponseLossUseOneRewardAndRejectChangedCommit() throws Exception {
        var start=start(day(1),connect());String t=token(start.path("runtimeCredential"));UUID s=id(start.path("session"),"sessionId");var cmd=command(s,1,t);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->send("POST",finalizePath(s,1),cmd,t,key()));var b=pool.submit(()->send("POST",finalizePath(s,1),cmd,t,key()));
            assertThat(accepted(a.get(40,TimeUnit.SECONDS),200)).isEqualTo(accepted(b.get(40,TimeUnit.SECONDS),200));
        }
        assertThat(count("receipt")).isEqualTo(1);assertThat(count("star_award")).isEqualTo(1);assertThat(count("story_event")).isEqualTo(1);
        var changed=new LinkedHashMap<>(cmd);changed.put("attemptId",UUID.randomUUID());assertCode(send("POST",finalizePath(s,1),changed,t,key()),"IDEMPOTENCY_CONFLICT");
    }
    @Test void finalizeFailureRollsBackReceiptStarsWorldCheckpointAndReplay() throws Exception {
        var start=start(day(1),connect());String t=token(start.path("runtimeCredential"));UUID s=id(start.path("session"),"sessionId");var cmd=command(s,1,t);
        String before=accepted(send("GET","/api/v1/sessions/"+s,null,t,null),200).toString();int replay=db.queryForObject("select count(*) from foundation.idempotency_record",Integer.class);
        db.execute("alter table learning.star_award add constraint synthetic_reject_award check(false) not valid");
        try{assertThat(send("POST",finalizePath(s,1),cmd,t,key()).statusCode()).isEqualTo(500);}finally{db.execute("alter table learning.star_award drop constraint synthetic_reject_award");}
        assertThat(count("receipt")).isZero();assertThat(count("star_award")).isZero();assertThat(count("story_event")).isZero();
        assertThat(db.queryForObject("select revision from learning.child_world where child_id=?",Long.class,child)).isZero();
        assertThat(accepted(send("GET","/api/v1/sessions/"+s,null,t,null),200).toString()).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from foundation.idempotency_record",Integer.class)).isEqualTo(replay);
        accepted(send("POST",finalizePath(s,1),cmd,t,key()),200);
    }
    @Test void midnightBlocksNewRewardButAllowsPastReceiptAndUnpreparedDaysDoNotUnlock() throws Exception {
        Day d=day(1);String pre=connect();var persist=accepted(send("POST","/api/v1/child-sessions/persist",device(),pre,key()),200);
        var start=start(d,pre);String t=token(start.path("runtimeCredential"));UUID s=id(start.path("session"),"sessionId");
        JsonNode first=accepted(send("POST",finalizePath(s,1),command(s,1,t),t,key()),200);var second=command(s,2,t);
        clock.now=Instant.parse("2026-11-02T15:00:00Z");String refreshed=refresh(persist.path("refreshToken").asText());String scoped=token(start(d,refreshed).path("runtimeCredential"));
        assertCode(send("POST",finalizePath(s,2),second,scoped,key()),"REWARD_WINDOW_EXPIRED");
        assertThat(accepted(send("GET",receiptPath(s,1),null,scoped,null),200)).isEqualTo(first);
        accepted(send("GET",homePath(),null,refreshed,null),200);accepted(send("POST",entryPath(),entry(),refreshed,key()),200);
        assertThat(db.queryForObject("select max(day_index) from learning.plan where child_id=? and unlocked_date is not null",Integer.class,child)).isEqualTo(1);
    }
    @Test void holdRevocationAndCapabilityWithdrawalBlockContentAndNewCompletionWithoutUndoingReceipt() throws Exception {
        Day d=day(1);var start=start(d,connect());String t=token(start.path("runtimeCredential"));UUID s=id(start.path("session"),"sessionId");
        var first=accepted(send("POST",finalizePath(s,1),command(s,1,t),t,key()),200);var cmd=command(s,2,t);
        db.update("update learning.material set status='ON_HOLD' where id=?",d.materials().get(1));
        assertCode(send("POST",finalizePath(s,2),cmd,t,key()),"CONTENT_NOT_APPROVED");
        db.update("update learning.material set status='CONFIRMED' where id=?",d.materials().get(1));
        accepted(send("POST","/api/v1/core/evidence/"+d.uses().get(1)+"/revoke",Map.of("reason","synthetic withdrawal"),adult(guardian),null),204);
        assertCode(send("POST",finalizePath(s,2),cmd,t,key()),"CONTENT_REVOKED");
        assertThat(accepted(send("GET",receiptPath(s,1),null,t,null),200)).isEqualTo(first);assertThat(count("star_award")).isEqualTo(1);
        authority.reviewers.clear();assertCode(send("GET","/api/v1/sessions/"+s+"/slots/1/content",null,t,null),"POLICY_REVIEW_FORBIDDEN");
    }
    @Test void leaseBindsSessionAssetHashDeviceAndGenerationAndExpires() throws Exception {
        Day d=day(1);var start=start(d,connect());String t=token(start.path("runtimeCredential"));UUID s=id(start.path("session"),"sessionId");
        var a=fixture.artifacts.values().stream().filter(x->x.kind().equals("MEDIA")).findFirst().orElseThrow();var request=Map.of("slot",1,"assetId",a.id(),"contentHash",a.bytesHash());
        var lease=accepted(send("POST","/api/v1/sessions/"+s+"/playback-leases",request,t,key()),201);UUID id=id(lease,"leaseId");
        assertThat(asset(s,a.id(),id,t).statusCode()).isEqualTo(200);
        media.corrupt=true;assertCode(asset(s,a.id(),id,t),"CONTENT_HASH_MISMATCH");media.corrupt=false;
        var other=fixture.artifacts.values().stream().filter(x->x.kind().equals("MEDIA")&&!x.id().equals(a.id())).findFirst().orElseThrow();
        assertCode(asset(s,other.id(),id,t),"EXECUTION_LEASE_EXPIRED");
        clock.now=clock.now.plusSeconds(30);assertCode(asset(s,a.id(),id,t),"EXECUTION_LEASE_EXPIRED");
        accepted(send("DELETE",base()+"/devices/synthetic-device",null,adult(guardian),key()),200);assertCode(asset(s,a.id(),id,t),"AUTH_REQUIRED");
    }
    @Test void leaseRenewalRechecksPinAndSupersedesOldTicket() throws Exception {
        var start=start(day(1),connect());String t=token(start.path("runtimeCredential"));UUID s=id(start.path("session"),"sessionId");
        var a=fixture.artifacts.values().stream().filter(x->x.kind().equals("MEDIA")).findFirst().orElseThrow();var request=Map.of("slot",1,"assetId",a.id(),"contentHash",a.bytesHash());
        var original=accepted(send("POST","/api/v1/sessions/"+s+"/playback-leases",request,t,key()),201);UUID lease=id(original,"leaseId");clock.now=clock.now.plusSeconds(10);
        String key=key();var next=accepted(send("POST","/api/v1/playback-leases/"+lease+"/renew",request,t,key),201);
        assertThat(accepted(send("POST","/api/v1/playback-leases/"+lease+"/renew",request,t,key),201)).isEqualTo(next);
        assertCode(asset(s,a.id(),lease,t),"EXECUTION_LEASE_EXPIRED");assertThat(asset(s,a.id(),id(next,"leaseId"),t).statusCode()).isEqualTo(200);
        clock.now=clock.now.plusSeconds(30);assertCode(asset(s,a.id(),id(next,"leaseId"),t),"EXECUTION_LEASE_EXPIRED");
    }
    @Test void approvalConsentRevisionAndPublicationFailureRollbackAreEnforced() throws Exception {
        Day d=day(1);String pre=connect();
        db.update("update care.consent set revision=revision+1 where child_id=?",child);
        String fresh=connect();assertCode(send("POST","/api/v1/daily-plans/"+d.plan()+"/sessions",startBody(),fresh,key()),"CONTENT_NOT_APPROVED");
        assertCode(send("GET",homePath(),null,pre,null),"AUTH_REQUIRED");
        var draft=draft(2,1);UUID summary=manuscripts.store(child,draft),v=validate(draft);String hash=content.hash(v);
        accepted(send("POST",base()+"/learning-material-confirmations",Map.of("summaryIds",List.of(summary),"decision","CONFIRM"),adult(guardian),key()),200);
        UUID review=evidenceId(send("POST",validationPath(v,"full-review"),evidence(hash),adult(reviewer),null));UUID use=evidenceId(send("POST",validationPath(v,"child-use"),useBody(hash),adult(guardian),null));
        int before=count("core_evidence");db.execute("alter table learning.core_evidence add constraint synthetic_reject_publication check(kind<>'PUBLICATION') not valid");
        try{assertThat(send("POST",validationPath(v,"publish"),publishBody(hash,review,use),adult(publisher),null).statusCode()).isEqualTo(500);}
        finally{db.execute("alter table learning.core_evidence drop constraint synthetic_reject_publication");}
        assertThat(count("core_evidence")).isEqualTo(before);assertThat(count("session")).isZero();
        var pub=send("POST",validationPath(v,"publish"),publishBody(hash,review,use),adult(publisher),null);UUID first=evidenceId(pub);
        assertThat(evidenceId(send("POST",validationPath(v,"publish"),publishBody(hash,review,use),adult(publisher),null))).isEqualTo(first);
    }
    @Test void sequentialPreparedDaysNeedNewExplicitEntryAndNeverSkipOrUnlockFromGet() throws Exception {
        Day first=day(1),third=day(3);String pre=connect();var persistent=accepted(send("POST","/api/v1/child-sessions/persist",device(),pre,key()),200);start(first,pre);
        clock.now=clock.now.plus(Duration.ofDays(1));pre=refresh(persistent.path("refreshToken").asText());
        accepted(send("POST",entryPath(),entry(),pre,key()),200);
        assertThat(db.queryForObject("select max(day_index) from learning.plan where child_id=? and unlocked_date is not null",Integer.class,child)).isEqualTo(1);
        Day second=day(2);var event=entry();accepted(send("GET",homePath(),null,pre,null),200);
        assertCode(send("POST","/api/v1/daily-plans/"+second.plan()+"/sessions",startBody(),pre,key()),"MATERIAL_NOT_READY");
        accepted(send("POST",entryPath(),event,pre,key()),200);start(second,pre);
        var another=accepted(send("POST","/api/v1/child-sessions/persist",device(),pre,key()),200);clock.now=clock.now.plus(Duration.ofDays(1));pre=refresh(another.path("refreshToken").asText());
        accepted(send("POST",entryPath(),event,pre,key()),200);
        assertThat(db.queryForObject("select max(day_index) from learning.plan where child_id=? and unlocked_date is not null",Integer.class,child)).isEqualTo(2);
        accepted(send("POST",entryPath(),entry(),pre,key()),200);start(third,pre);
        assertThat(count("star_award")).isZero();
    }
    @Test void planValidatesAllBranchWorldPreconditionsAndKeepsCoreStringEffects() throws Exception {
        fixture.scenario.withArray("preconditions").addObject().put("key","picnic_prepared").put("op","NOT_EXISTS").putNull("value");
        var invalid=day(1,false);
        assertCode(send("POST",base()+"/core-plans",Map.of("dayIndex",1,"timezone","Asia/Seoul","publicationIds",invalid.publications()),adult(publisher),null),"NARRATIVE_PATH_UNAVAILABLE");
        fixture.scenario.withArray("preconditions").removeAll();
        fixture.scenario.withArray("terminalEffects").addObject().put("key","picnic_location").put("op","SET").put("value","INDOOR");
        var d=day(2,false);
        UUID plan=id(accepted(send("POST",base()+"/core-plans",Map.of("dayIndex",1,"timezone","Asia/Seoul","publicationIds",d.publications()),adult(publisher),null),201),"planId");
        assertThat(count("receipt")).isZero();
        assertThat(db.queryForObject("select paths->0->'effects'->>'picnic_location' from learning.core_dependency_validation where id=?",String.class,d.validations().getFirst())).isEqualTo("INDOOR");
        var started=start(new Day(plan,d.materials(),d.validations(),d.publications(),d.uses()),connect());String token=token(started.path("runtimeCredential"));UUID session=id(started.path("session"),"sessionId");
        accepted(send("POST",finalizePath(session,1),command(session,1,token),token,key()),200);
        assertThat(db.queryForObject("select effects->>'picnic_location' from learning.story_event",String.class)).isEqualTo("INDOOR");
    }
    @Test void concurrentGrantExchangeConsumesOnceAndHashBoundSourceCannotBeForged() throws Exception {
        var grant=accepted(send("POST",base()+"/device-grants",device(),adult(guardian),key()),201);var request=Map.of("deviceId","synthetic-device","oneTimeCode",grant.path("oneTimeCode").asText());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->send("POST","/api/v1/child-sessions/exchange",request,null,key()));var b=pool.submit(()->send("POST","/api/v1/child-sessions/exchange",request,null,key()));
            assertThat(List.of(a.get(40,TimeUnit.SECONDS).statusCode(),b.get(40,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,401);
        }
        assertThat(count("runtime_credential")).isEqualTo(1);
        var d=draft(1,1);var node=(tools.jackson.databind.node.ObjectNode)json.readTree(d.payload());node.put("contentHash","0".repeat(64));
        var forged=new CoreManuscriptStore.Draft(d.materialId(),d.scenarioVersionId(),d.sourceMaterialId(),d.subject(),d.summary(),node.toString(),d.profile());manuscripts.store(child,forged);
        assertThatThrownBy(()->validate(forged)).isInstanceOf(ApiException.class);assertThat(count("core_dependency_validation")).isZero();
    }
    @Test void revocationRacesFinalizeAndEveryOutcomeIsAtomic() throws Exception {
        Day d=day(1);var start=start(d,connect());String t=token(start.path("runtimeCredential"));UUID s=id(start.path("session"),"sessionId");var cmd=command(s,1,t);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->send("POST",finalizePath(s,1),cmd,t,key()));var b=pool.submit(()->send("DELETE",base()+"/devices/synthetic-device",null,adult(guardian),key()));
            int status=a.get(40,TimeUnit.SECONDS).statusCode();assertThat(status).isIn(200,401);accepted(b.get(40,TimeUnit.SECONDS),200);
            int awards=count("star_award");assertThat(count("receipt")).isEqualTo(awards);assertThat(count("story_event")).isEqualTo(awards);assertThat(awards).isBetween(0,1);
        }
        assertCode(send("POST",finalizePath(s,1),cmd,t,key()),"AUTH_REQUIRED");
    }
    private Day day(int day) throws Exception {
        return day(day,true);
    }
    private Day day(int day,boolean planReady) throws Exception {
        db.update("update identity.auth_session set reauthenticated_at=?",ts());
        var materials=new ArrayList<UUID>();var validations=new ArrayList<UUID>();var summaries=new ArrayList<UUID>();var publications=new ArrayList<UUID>();var uses=new ArrayList<UUID>();
        for(int slot=1;slot<=5;slot++){var d=draft(day,slot);materials.add(d.materialId());summaries.add(manuscripts.store(child,d));validations.add(validate(d));}
        accepted(send("POST",base()+"/learning-material-confirmations",Map.of("summaryIds",summaries,"decision","CONFIRM"),adult(guardian),key()),200);
        for(UUID v:validations) {String hash=content.hash(v);UUID review=evidenceId(send("POST",validationPath(v,"full-review"),evidence(hash),adult(reviewer),null));
            UUID use=evidenceId(send("POST",validationPath(v,"child-use"),useBody(hash),adult(guardian),null));uses.add(use);
            publications.add(evidenceId(send("POST",validationPath(v,"publish"),publishBody(hash,review,use),adult(publisher),null)));}
        UUID plan=null;
        if(planReady)plan=id(accepted(send("POST",base()+"/core-plans",Map.of("dayIndex",day,"timezone","Asia/Seoul","publicationIds",publications),adult(publisher),null),201),"planId");
        return new Day(plan,materials,validations,publications,uses);
    }
    private CoreManuscriptStore.Draft draft(int day,int slot){return new CoreManuscriptStore.Draft(UUID.randomUUID(),UUID.randomUUID(),"source-"+day+"-"+slot,"합성 주제","고정 합성 요약",fixture.manuscript(day,slot),CoreScenarioValidator.Profile.GENERATED_1_TO_3);}
    private UUID validate(CoreManuscriptStore.Draft d){return content.validate(d.materialId(),fixture.artifacts.values().stream().map(CoreDependencyValidator.Artifact::id).toList(),fixture.cues);}
    private String connect() throws Exception {db.update("update identity.auth_session set reauthenticated_at=? where id=?",ts(),guardian.session());var g=accepted(send("POST",base()+"/device-grants",device(),adult(guardian),key()),201);return token(accepted(send("POST","/api/v1/child-sessions/exchange",Map.of("deviceId","synthetic-device","oneTimeCode",g.path("oneTimeCode").asText()),null,key()),200));}
    private String refresh(String value) throws Exception{return token(accepted(send("POST","/api/v1/child-sessions/refresh",Map.of("deviceId","synthetic-device","refreshToken",value),null,key()),200).path("runtimeCredential"));}
    private JsonNode start(Day d,String pre) throws Exception{return accepted(send("POST","/api/v1/daily-plans/"+d.plan()+"/sessions",startBody(),pre,key()),201,200);}
    private Map<String,Object> checkpointRequest(UUID session,String token) throws Exception {
        var state=accepted(send("GET","/api/v1/sessions/"+session,null,token,null),200);var current=state.path("checkpoint");var cp=new LinkedHashMap<String,Object>();
        cp.put("checkpointRevision",current.path("checkpointRevision").asLong());cp.put("slot",current.path("slot").asInt());cp.put("attemptId",current.path("attemptId").asText());
        cp.put("manifestHash",state.path("pin").path("planManifestHash").asText());cp.put("worldRevision",state.path("session").path("worldRevision").asLong());
        cp.put("selectedChoiceIds",List.of("mat"));cp.put("sceneId","end-mat");cp.put("terminalSceneId","end-mat");cp.put("selectedEmotionId","CURIOUS");cp.put("draftVersion",current.path("draftVersion").asLong());return cp;
    }
    private Map<String,Object> command(UUID session,int slot,String token) throws Exception {
        var cp=checkpointRequest(session,token);long draft=((Number)cp.get("draftVersion")).longValue()+1;cp.put("draftVersion",draft);
        var proof=accepted(send("POST","/api/v1/sessions/"+session+"/slots/"+slot+"/confirmed-expressions",Map.of("text","합성 원문은 보관 금지","draftVersion",draft,"inputSource","TYPED","childConfirmed",true),token,key()),201);
        cp.put("confirmedExpressionRef",proof.path("confirmedExpressionRef").asText());var checkpoint=accepted(send("PUT",checkpointPath(session),cp,token,key()),200);
        var state=accepted(send("GET","/api/v1/sessions/"+session,null,token,null),200).path("session");var completion=new LinkedHashMap<String,Object>();
        completion.put("confirmedExpressionRef",proof.path("confirmedExpressionRef").asText());completion.put("draftVersion",draft);completion.put("selectedChoiceIds",List.of("mat"));
        completion.put("terminalSceneId","end-mat");completion.put("selectedEmotionId","CURIOUS");completion.put("expressionChoice","ORIGINAL");
        completion.put("expectedSessionRevision",state.path("revision").asLong());completion.put("expectedWorldRevision",state.path("worldRevision").asLong());completion.put("clientCommitId",key());
        return Map.of("attemptId",cp.get("attemptId"),"manifestHash",cp.get("manifestHash"),"scenarioVersionId",state.path("slots").get(slot-1).path("scenarioVersionId").asText(),"expectedCheckpointRevision",checkpoint.path("checkpointRevision").asLong(),"completion",completion);
    }
    private User user(String role){UUID id=UUID.randomUUID(),s=UUID.randomUUID();db.update("insert into identity.member(id,email,display_name,password_hash,requested_role,role,status,email_verified_at,created_at,updated_at) values(?,?,?,'synthetic',?,?,'ACTIVE',?,?,?)",id,id+"@example.test","SYNTHETIC",role.equals("ADMIN")?null:role,role,ts(),ts(),ts());db.update("insert into identity.auth_session(id,member_id,created_at,expires_at,reauthenticated_at,verification_ref) values(?,?,?,?,?,?)",s,id,ts(),Timestamp.from(clock.now.plus(Duration.ofDays(60))),ts(),UUID.randomUUID());return new User(id,s);}
    private String adult(User u){return jwt.issue(u.id(),u.session(),clock.now.plus(Duration.ofDays(60))).accessToken();}
    private HttpResponse<String> send(String method,String path,Object body,String token,String key) throws Exception {var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(35));if(token!=null)r.header("Authorization","Bearer "+token);if(key!=null)r.header("Idempotency-Key",key);if(body!=null)r.header("Content-Type","application/json");return client.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> asset(UUID s,UUID a,UUID lease,String token)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/sessions/"+s+"/content/1/assets/"+a)).header("Authorization","Bearer "+token).header("X-Playback-Lease",lease.toString()).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private JsonNode accepted(HttpResponse<String> r,int...status){assertThat(Arrays.stream(status).boxed().toList()).as(r.body()).contains(r.statusCode());return r.body().isBlank()?json.createObjectNode():json.readTree(r.body());}
    private void assertCode(HttpResponse<String> r,String code){assertThat(r.statusCode()).as(r.body()).isEqualTo(ErrorCode.valueOf(code).status());assertThat(json.readTree(r.body()).path("code").asText()).isEqualTo(code);}
    private UUID evidenceId(HttpResponse<String> r){return id(accepted(r,201),"evidenceId");}
    private UUID id(JsonNode n,String name){return UUID.fromString(n.path(name).asText());}
    private String token(JsonNode n){return n.path("credential").path("accessToken").asText();}
    private Map<String,Object> useBody(String hash){return Map.of("manifestHash",hash,"reference","synthetic-explicit-use","childUseAllowed",true);}
    private Map<String,Object> evidence(String hash){return Map.of("manifestHash",hash,"reference","synthetic-full-scope-review","allBranchesAndMediaReviewed",true);}
    private Map<String,Object> publishBody(String hash,UUID review,UUID use){return Map.of("manifestHash",hash,"reference","synthetic-publication","reviewId",review,"childUseId",use);}
    private Map<String,Object> device(){return Map.of("schemaVersion","0.3.0","deviceId","synthetic-device");}
    private Map<String,Object> startBody(){return Map.of("schemaVersion","0.3.0","executionMode","REGULAR","deviceId","synthetic-device");}
    private Map<String,Object> entry(){return Map.of("schemaVersion","0.3.0","entryPoint","HOME","eventId",UUID.randomUUID(),"deviceId","synthetic-device");}
    private String base(){return "/api/v1/children/"+child;}private String homePath(){return base()+"/learning-home";}private String entryPath(){return base()+"/mission-entries";}
    private String validationPath(UUID v,String suffix){return "/api/v1/core/validations/"+v+"/"+suffix;}
    private String checkpointPath(UUID s){return "/api/v1/sessions/"+s+"/checkpoint";}private String finalizePath(UUID s,int slot){return "/api/v1/sessions/"+s+"/loops/"+slot+"/finalize";}private String receiptPath(UUID s,int slot){return "/api/v1/sessions/"+s+"/loops/"+slot+"/receipt";}
    private int count(String table){return db.queryForObject("select count(*) from learning."+table,Integer.class);}private String key(){return UUID.randomUUID().toString();}private Timestamp ts(){return Timestamp.from(clock.now);}
    private void export(String file,Object value)throws Exception{var dir=java.nio.file.Path.of("build/core-phase2-3");java.nio.file.Files.createDirectories(dir);java.nio.file.Files.writeString(dir.resolve(file),json.writeValueAsString(value));}
}

