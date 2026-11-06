package com.planb.emotionfriend.api.agent;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.planb.emotionfriend.api.common.error.*;

/** Fixed-origin one-shot HTTP adapter. No redirect, retry, browser token or provider credentials. */
@Component
public final class AgentWorkerClient {
    private final URI origin;private final String token;private final boolean enabled;private final JsonMapper json;
    @Value("${emotion-friend.agent.v2-enabled:false}") private boolean v2;
    public boolean statefulEnabled(){return v2;}
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public AgentWorkerClient(JsonMapper json,@Value("${emotion-friend.agent.url:http://127.0.0.1:5198}") String url,
            @Value("${emotion-friend.agent.token:}") String token,@Value("${emotion-friend.agent.enabled:false}") boolean enabled) {
        this.json=json;this.origin=URI.create(url);this.token=token;this.enabled=enabled;
        if(origin.getHost()==null || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null
                || origin.getPath()!=null && !origin.getPath().isEmpty() && !origin.getPath().equals("/")
                || !(origin.getScheme().equals("https") || origin.getScheme().equals("http") && Set.of("127.0.0.1","localhost").contains(origin.getHost())))
            throw new IllegalArgumentException("Agent origin must be HTTPS or loopback HTTP without credentials/path");
        if(enabled && token.length()<32)throw new IllegalArgumentException("Agent service token must be configured");
    }
    public void requireEnabled(){if(!enabled)throw new ApiException(ErrorCode.WORKER_UNAVAILABLE);}
    public JsonNode execute(String path,JsonNode input) {
        requireEnabled();
        if(!Set.of("/internal/v1/daily-scenarios","/internal/v1/training-summary").contains(path))throw new IllegalArgumentException("Unregistered worker operation");
        if(v2&&path.equals("/internal/v1/daily-scenarios"))return executeStateful(input);
        try {
            var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(245))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(input))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                byte[] bytes=stream.readNBytes(2*1024*1024+1);
                if(bytes.length>2*1024*1024)throw new UnknownOutcome();
                if(response.statusCode()!=200)throw new WorkerRejected();
                return json.readTree(bytes);
            }
        } catch(WorkerRejected|UnknownOutcome e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new UnknownOutcome();}
        catch(Exception e){throw new UnknownOutcome();}
    }
    private JsonNode executeStateful(JsonNode input) {
        String path="/internal/v2/agent-jobs";long end=System.nanoTime()+Duration.ofMinutes(29).toNanos();boolean submitted=false;
        try {
            while(System.nanoTime()<end) {
                var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token);
                if(!submitted)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(input)));
                else builder.GET();
                var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
                try(var stream=response.body()) {
                    byte[] bytes=stream.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new UnknownOutcome();
                    var body=json.readTree(bytes);
                    if(response.statusCode()==200)return body;
                    if(response.statusCode()!=202){if(body.path("code").asText().equals("OUTCOME_UNKNOWN"))throw new UnknownOutcome();throw new WorkerRejected();}
                }
                submitted=true;path="/internal/v2/agent-jobs/"+input.path("jobId").asText();Thread.sleep(1000);
            }
            throw new UnknownOutcome();
        }catch(WorkerRejected|UnknownOutcome e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new UnknownOutcome();}
        catch(Exception e){throw new UnknownOutcome();}
    }
    public static final class UnknownOutcome extends RuntimeException {}
    public JsonNode mediaConfig(){return mediaExchange("/internal/v2/media-config",null);}
    public JsonNode runtimeStatus(){return mediaExchange("/internal/v2/worker-status",null);}
    public JsonNode sentenceMedia(JsonNode request){return mediaExchange("/internal/v2/sentence-media",request);}
    public JsonNode runtimeExpression(JsonNode request){return mediaExchange("/internal/v2/runtime-expression",request);}
    private JsonNode mediaExchange(String path,JsonNode body) {
        requireEnabled();if(!v2)throw new WorkerRejected("V2_NOT_ENABLED");
        try {
            var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(path.equals("/internal/v2/worker-status")?5:path.equals("/internal/v2/runtime-expression")?100:50)).header("Authorization","Bearer "+token);
            if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                byte[] bytes=stream.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new UnknownOutcome();
                var result=json.readTree(bytes);
                if(response.statusCode()!=200){String code=result.path("error").path("code").asText(result.path("code").asText("WORKER_REJECTED"));throw new WorkerRejected(code.matches("[A-Z_]{1,80}")?code:"WORKER_REJECTED");}
                return result;
            }
        }catch(WorkerRejected|UnknownOutcome e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new UnknownOutcome();}catch(Exception e){throw new UnknownOutcome();}
    }
    public static final class WorkerRejected extends RuntimeException {
        private final String code;public WorkerRejected(){this("WORKER_REJECTED");}public WorkerRejected(String code){this.code=code;}public String code(){return code;}
    }
}
