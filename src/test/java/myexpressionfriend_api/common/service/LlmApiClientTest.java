package myexpressionfriend_api.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import myexpressionfriend_api.common.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LlmApiClientTest {
    private LlmProperties properties;
    private LlmApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://generation.example/custom/v1/");
        properties.setApiKey("generation-test-key");
        properties.setMaxBackoffSeconds(0);
        client = new LlmApiClient(properties, new ObjectMapper());
        server = MockRestServiceServer.bindTo((RestTemplate)
                ReflectionTestUtils.getField(client, "restTemplate")).build();
    }

    @Test
    void sendsOpenAiCompatibleRequestToConfiguredProvider() {
        server.expect(requestTo("https://generation.example/custom/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer generation-test-key"))
                .andExpect(content().json("""
                        {"model":"provider-model","messages":[{"role":"user","content":"안녕"}],"stream":false}
                        """, true))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"반가워요"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.generateText("provider-model", "안녕")).contains("반가워요");
        server.verify();
    }

    @Test
    void mapsLegacyThinkAndReadsTextContentParts() {
        server.expect(requestTo("https://generation.example/custom/v1/chat/completions"))
                .andExpect(jsonPath("$.reasoning_effort").value("medium"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}}]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.generateText("model", "prompt", new LlmGenerateOptions("true")))
                .contains("onetwo");
        server.verify();
    }

    @Test
    void retriesRateLimitButDoesNotRetryInvalidCredentials() {
        server.expect(requestTo("https://generation.example/custom/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://generation.example/custom/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(client.generateText("model", "prompt")).isEmpty();
        server.verify();
    }

    @Test
    void disabledGenerationDoesNotCallProvider() {
        properties.setEnabled(false);
        assertThat(client.generateText("model", "prompt")).isEmpty();
        server.verify();
    }

    @Test
    void refusalOrEmptyResponseIsNotReturnedAsGeneratedText() {
        server.expect(requestTo("https://generation.example/custom/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":null,"refusal":"refused"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.generateText("model", "prompt")).isEmpty();
        server.verify();
    }
}
