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
            Flyway.configure().dataSource(isolated,"foundation_test",password).target("5").load().migrate();
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
            var before=snapshots(db);
            var flyway=Flyway.configure().dataSource(isolated,"foundation_test",password).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(snapshots(db)).isEqualTo(before);
            assertThat(db.queryForObject("select content_origin from learning.material",String.class)).isEqualTo("LEGACY_REVIEWED");
            assertThat(db.queryForObject("select count(*) from learning.core_manuscript",Integer.class)).isZero();
            assertThat(db.queryForObject("select encrypted_body from foundation.idempotency_record",byte[].class)).containsExactly(encrypted);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {
            // Only the fresh UUID-named database created by this test can be dropped.
            control.execute("drop database "+database);
        }
    }
    private Map<String,List<String>> snapshots(JdbcTemplate db) {
        var result=new LinkedHashMap<String,List<String>>();
        for(String table:List.of("learning.material","learning.plan","learning.plan_slot","learning.session","learning.receipt","learning.star_award","learning.story_event","foundation.idempotency_record")) {
            String projection=table.equals("learning.material")?"to_jsonb(t)-'content_origin'":"to_jsonb(t)";
            result.put(table,db.queryForList("select ("+projection+")::text from "+table+" t order by 1",String.class));
        }
        return result;
    }
}
