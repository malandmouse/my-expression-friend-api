package com.planb.emotionfriend.api.learning;

import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;

/** Upgrade rehearsal in its own synthetic database; never cleans a caller's database. */
class CoreMigrationIntegrationTest {
    @Test void upgradingV5PreservesLegacyIdsContentRewardsReceiptAndEncryptedReplay() {
        upgrade("6",1,false);
    }
    @Test void upgradingV5ThroughV8PreservesRowsHashesIdsRewardsReceiptAndReplay() {
        upgrade("8",3,false);
    }
    @Test void inconsistentLegacyWorldBlocksV8AndRollsBackWithoutRepair() {
        upgrade("8",3,true);
    }
    @Test void upgradingV6PreservesFullCoreManuscriptAndOriginalValidationEvidence() {
        upgrade("8",2,false,true);
    }
    private void upgrade(String target,int migrations,boolean corrupt) {
        upgrade(target,migrations,corrupt,false);
    }
    private void upgrade(String target,int migrations,boolean corrupt,boolean coreRows) {
        String url=System.getenv("EF_TEST_DB_URL");
        if(url==null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/foundation_test"))
            throw new IllegalStateException("Explicit isolated loopback foundation_test required");
        String password=System.getenv().getOrDefault("EF_TEST_DB_PASSWORD","");
        var control=new JdbcTemplate(new DriverManagerDataSource(url,"foundation_test",password));
        String database="core_upgrade_"+UUID.randomUUID().toString().replace("-","");
        control.execute("create database "+database);
        try {
            String isolated=url.substring(0,url.lastIndexOf('/')+1)+database;
            var db=new JdbcTemplate(new DriverManagerDataSource(isolated,"foundation_test",password));
            Flyway.configure().dataSource(isolated,"foundation_test",password).target(coreRows?"6":"5").load().migrate();
            UUID member=UUID.randomUUID(),child=UUID.randomUUID(),material=UUID.randomUUID(),scenario=UUID.randomUUID(),
                    summary=UUID.randomUUID(),plan=UUID.randomUUID(),session=UUID.randomUUID(),receipt=UUID.randomUUID();
            db.update("insert into identity.member(id,email,display_name,password_hash,requested_role,role,status,email_verified_at,created_at,updated_at) values(?,'upgrade@example.test','SYNTHETIC','unused','GUARDIAN','GUARDIAN','ACTIVE',now(),now(),now())",member);
            db.update("insert into care.child(id,guardian_id,pseudonym,profile,profile_version,created_at,updated_at) values(?,?,'SYNTHETIC','{}',1,now(),now())",child,member);
            db.update("insert into learning.material(id,child_id,summary_id,scenario_version_id,subject,summary,content_hash,review_reference,content,created_at) values(?,?,?,?,'SYNTHETIC','SYNTHETIC',?,'synthetic-review','{}',now())",material,child,summary,scenario,"a".repeat(64));
            db.update("insert into learning.plan(id,child_id,day_index,timezone,publication_reference,published_at) values(?,?,1,'Asia/Seoul','synthetic-publication',now())",plan,child);
            db.update("insert into learning.plan_slot(plan_id,slot,material_id,content_hash) values(?,1,?,?)",plan,material,"a".repeat(64));
            db.update("insert into learning.session(id,plan_id,device_id,local_date,reward_expires_at,revision,world_revision,stars,completed_count,started_at) values(?,?,'synthetic',current_date,now()+interval '1 day',2,1,1,1,now())",session,plan);
            String snapshot="{\"receiptId\":\""+receipt+"\",\"stars\":1,\"worldRevision\":1}";
            db.update("insert into learning.receipt(id,session_id,plan_id,slot,client_commit_id,request_digest,response,completed_at) values(?,?,?,1,'synthetic-commit',?,?::jsonb,now())",receipt,session,plan,"b".repeat(64),snapshot);
            db.update("insert into learning.star_award(receipt_id,session_id,slot,amount) values(?,?,1,1)",receipt,session);
            db.update("insert into learning.story_event(receipt_id,child_id,world_revision,effects,recorded_at) values(?,?,1,'{\"npc_helped\":true}',now())",receipt,child);
            byte[] encrypted=new byte[32];Arrays.fill(encrypted,(byte)7);
            db.update("insert into foundation.idempotency_record(actor_scope,method,path,key_digest,request_digest,status_code,content_type,encrypted_body) values('synthetic','POST','/synthetic',?,?,200,'application/json',?)","c".repeat(64),"d".repeat(64),encrypted);
            if(coreRows) {
                try {
                    var fixture=new CoreSyntheticFixture();var validated=new CoreScenarioValidator().validate(fixture.manuscript(1,1),CoreScenarioValidator.Profile.GENERATED_1_TO_3);
                    UUID core=UUID.randomUUID(),version=UUID.randomUUID();var mapper=new tools.jackson.databind.json.JsonMapper();
                    var projection=new LearningCatalog.ReviewedMaterial(core,version,"SYNTHETIC CORE","SYNTHETIC CORE SUMMARY","CORE_DRAFT_STRUCTURE_ONLY",List.of());String content=mapper.writeValueAsString(projection);
                    db.update("insert into learning.material(id,child_id,summary_id,scenario_version_id,subject,summary,content_hash,review_reference,content,content_origin,created_at) values(?,?,?,?,?,?,?,'CORE_DRAFT_STRUCTURE_ONLY',?::jsonb,'CORE_DRAFT',now())",core,child,UUID.randomUUID(),version,projection.subject(),projection.summary(),LearningCatalog.hash(content),content);
                    db.update("insert into learning.core_manuscript values(?,?,?,?,?,?,?,?,?,?,now())",core,child,version,"synthetic-source-material",validated.sourceScenarioVersionId(),validated.sourceContentHash(),validated.payloadHash(),validated.payload(),"e".repeat(64),"GENERATED_1_TO_3");
                    db.update("insert into learning.core_manuscript_validation values(?,?,?,?::jsonb,now())",core,validated.payloadHash(),CoreScenarioValidator.VERSION,mapper.writeValueAsString(validated.report()));
                }catch(Exception e){throw new IllegalStateException(e);}
            }
            if(corrupt) {
                db.update("update learning.story_event set world_revision=2");
                db.update("update learning.receipt set response=jsonb_set(response,'{worldRevision}','2')");
            }
            var before=snapshots(db);
            var flyway=Flyway.configure().dataSource(isolated,"foundation_test",password).target(target).load();
            if(corrupt) {
                assertThatThrownBy(flyway::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class).hasStackTraceContaining("Legacy learning evidence inconsistent");
                assertThat(snapshots(db)).isEqualTo(before);
                assertThat(db.queryForObject("select to_regclass('learning.child_world') is null",Boolean.class)).isTrue();
                assertThat(db.queryForObject("select count(*) from information_schema.columns where table_schema='learning' and table_name='plan' and column_name='unlocked_date'",Integer.class)).isZero();
                return;
            }
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(migrations);
            assertThat(snapshots(db)).isEqualTo(before);
            assertThat(db.queryForObject("select content_origin from learning.material where id=?",String.class,material)).isEqualTo("LEGACY_REVIEWED");
            assertThat(db.queryForObject("select count(*) from learning.core_manuscript",Integer.class)).isEqualTo(coreRows?1:0);
            assertThat(db.queryForObject("select encrypted_body from foundation.idempotency_record",byte[].class)).containsExactly(encrypted);
            if(target.equals("8")) {
                assertThat(db.queryForObject("select revision from learning.child_world where child_id=?",Long.class,child)).isEqualTo(1);
                assertThat(db.queryForObject("select unlocked_date from learning.plan where id=?",java.sql.Date.class,plan)).isEqualTo(db.queryForObject("select local_date from learning.session where id=?",java.sql.Date.class,session));
                var path=java.nio.file.Path.of(coreRows?"build/core-phase2-3/migration-v6.json":"build/core-phase2-3/migration.json");
                try {java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.writeString(path,new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of("tables",before,"beforeHash",CoreDependencyValidator.sha(before.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"afterHash",CoreDependencyValidator.sha(snapshots(db).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"childWorldRevision",1,"isolatedDatabase",database)));}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {
            // Only the fresh UUID-named database created by this test can be dropped.
            control.execute("drop database "+database);
        }
    }
    private Map<String,List<String>> snapshots(JdbcTemplate db) {
        var result=new LinkedHashMap<String,List<String>>();
        for(String table:List.of("learning.material","learning.plan","learning.plan_slot","learning.session","learning.receipt","learning.star_award","learning.story_event","foundation.idempotency_record")) {
            String projection=switch(table) {case "learning.material"->"to_jsonb(t)-'content_origin'";
                case "learning.plan"->"to_jsonb(t)-'manifest_hash'-'manifest'-'unlocked_date'-'unlocked_at'";
                case "learning.plan_slot"->"to_jsonb(t)-'publication_id'";
                case "learning.receipt"->"to_jsonb(t)-'runtime_request_digest'";default->"to_jsonb(t)";};
            result.put(table,db.queryForList("select ("+projection+")::text from "+table+" t order by 1",String.class));
        }
        for(String table:List.of("learning.core_manuscript","learning.core_manuscript_validation")) {
            boolean exists=Boolean.TRUE.equals(db.queryForObject("select to_regclass(?) is not null",Boolean.class,table));
            result.put(table,exists?db.queryForList("select to_jsonb(t)::text from "+table+" t order by 1",String.class):List.of());
        }
        return result;
    }
}
