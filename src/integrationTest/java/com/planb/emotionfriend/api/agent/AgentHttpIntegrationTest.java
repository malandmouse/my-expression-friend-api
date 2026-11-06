package com.planb.emotionfriend.api.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.planb.emotionfriend.api.common.FoundationIntegrationTest;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/auth/currentness/job/admission regression; fake worker only. Isolated foundation_test DB required. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentHttpIntegrationTest {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r){FoundationIntegrationTest.isolatedPostgres(r);}
    @Autowired JdbcTemplate db;@Autowired JsonMapper json;@Autowired PasswordEncoder passwords;
    @MockitoBean AgentWorkerClient worker;
    @LocalServerPort int port;
    static final String PASSWORD="SyntheticLocalAgent!2026";
    UUID guardian,child;String token;
    @BeforeEach void setup() throws Exception {
        db.execute("truncate care.child,identity.member,identity.auth_rate_bucket cascade");db.update("delete from foundation.idempotency_record");
        guardian=member("owner@local-agent.test");child=UUID.randomUUID();
        String profile=json.writeValueAsString(Map.of("schemaVersion","0.3.0","gender","BOY","ageBand","TARGET_8_TO_13","primaryLanguage","ko","interests",List.of(),"preferredActivities",List.of(),"avoidTopics",List.of()));
        db.update("insert into care.child(id,guardian_id,pseudonym,profile,profile_version,created_at,updated_at) values(?,?,'SYNTHETIC',?::jsonb,1,now(),now())",child,guardian,profile);
        db.update("insert into care.consent(child_id,id,scope,granted,notice_version,verification_ref,recorded_at,revision) values(?,?,'CORE_PROFILE_LEARNING',true,'synthetic',?,now(),1)",child,UUID.randomUUID(),UUID.randomUUID());
        token=login("owner@local-agent.test");
        when(worker.execute(eq("/internal/v1/daily-scenarios"),any())).thenAnswer(call->{
            JsonNode input=call.getArgument(1);try(var in=getClass().getResourceAsStream("/core/generated-st-day.json")){
                var result=(tools.jackson.databind.node.ObjectNode)json.readTree(in);
                result.put("jobId",input.path("jobId").asText());result.set("sourceSnapshot",input.path("sourceSnapshot"));
                ((tools.jackson.databind.node.ObjectNode)result.path("result")).put("jobId",input.path("jobId").asText());return result;
            }
        });
    }
    UUID member(String email){UUID id=UUID.randomUUID();db.update("insert into identity.member(id,email,display_name,password_hash,requested_role,role,status,email_verified_at,created_at,updated_at) values(?,?,'SYNTHETIC',?,'GUARDIAN','GUARDIAN','ACTIVE',now(),now(),now())",id,email,passwords.encode(PASSWORD));return id;}
    String login(String email)throws Exception{return json.readTree(send("POST","/api/v1/auth/login",Map.of("loginId",email,"password",PASSWORD),null).body()).path("credential").path("accessToken").asText();}
    HttpResponse<String> send(String method,String path,Object body,String bearer)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        if(body==null)b.GET();else b.header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try(var c=HttpClient.newHttpClient()){return c.send(b.build(),HttpResponse.BodyHandlers.ofString());}
    }
    Map<String,Object> generation(){return Map.of("programDay",1,"localDate","2026-11-05","characterPreference","BOTH","priorityGoals",List.of("EMOTION_CONTEXT","SELF_FEELING"),"dailyEvents",List.of());}
    @Test void guardianCreatesFiveDraftsAndSummaryConfirmationNeverPublishes()throws Exception{
        var response=send("POST","/api/v1/children/"+child+"/agent-jobs",generation(),token);assertThat(response.statusCode()).isEqualTo(202);
        String id=json.readTree(response.body()).path("jobId").asText(),state="";
        for(int i=0;i<100;i++){
            var status=send("GET","/api/v1/children/"+child+"/agent-jobs/"+id,null,token);assertThat(status.statusCode()).isEqualTo(200);
            state=json.readTree(status.body()).path("state").asText();if(!Set.of("QUEUED","RUNNING").contains(state))break;Thread.sleep(50);
        }
        assertThat(state).isEqualTo("RESULT_READY");
        var summaries=send("GET","/api/v1/children/"+child+"/learning-material-summaries",null,token);assertThat(summaries.statusCode()).isEqualTo(200);
        var items=json.readTree(summaries.body()).path("items");assertThat(items.size()).isEqualTo(5);
        List<String> ids=new ArrayList<>();for(var item:items)ids.add(item.path("summaryId").asText());
        assertThat(send("POST","/api/v1/children/"+child+"/learning-material-confirmations",Map.of("summaryIds",ids,"decision","CONFIRM"),token).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("select count(*) from learning.plan",Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from learning.receipt",Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from learning.core_evidence",Integer.class)).isZero();
    }
    @Test void anotherGuardianCannotDispatchForThisChild()throws Exception{
        member("other@local-agent.test");String other=login("other@local-agent.test");
        assertThat(send("POST","/api/v1/children/"+child+"/agent-jobs",generation(),other).statusCode()).isIn(403,404);
        verify(worker,never()).execute(anyString(),any());
    }
    @Test void missingCoreConsentBlocksWorker()throws Exception{
        db.update("update care.consent set granted=false,withdrawn_at=now(),revision=revision+1 where child_id=?",child);
        var response=send("POST","/api/v1/children/"+child+"/agent-jobs",generation(),token);
        assertThat(response.statusCode()).isEqualTo(403);verify(worker,never()).execute(anyString(),any());
    }
}
