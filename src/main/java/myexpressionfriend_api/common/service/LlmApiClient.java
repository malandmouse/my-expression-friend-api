package myexpressionfriend_api.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import myexpressionfriend_api.common.config.LlmProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class LlmApiClient implements LlmTextClient {

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public LlmApiClient(LlmProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(llmProperties.getConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(llmProperties.getReadTimeoutSeconds() * 1000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public Optional<String> generateText(String model, String prompt, LlmGenerateOptions options) {
        if (!llmProperties.isEnabled()) {
            return Optional.empty();
        }

        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("LLM model must be configured.");
        }
        for (int attempt = 0; attempt <= llmProperties.getMaxRetries(); attempt++) {
            try {
                return callGenerate(model, prompt, options);
            } catch (HttpClientErrorException ex) {
                if (ex.getStatusCode().value() == 429 && attempt < llmProperties.getMaxRetries()) {
                    sleepBackoff(attempt);
                    continue;
                }
                log.warn("LLM call failed. status={}", ex.getStatusCode().value());
                return Optional.empty();
            } catch (HttpServerErrorException ex) {
                if (attempt < llmProperties.getMaxRetries()) {
                    sleepBackoff(attempt);
                    continue;
                }
                log.warn("LLM call failed. status={}", ex.getStatusCode().value());
                return Optional.empty();
            } catch (Exception ex) {
                if (attempt < llmProperties.getMaxRetries()) {
                    sleepBackoff(attempt);
                    continue;
                }
                log.warn("LLM call failed. type={}", ex.getClass().getSimpleName());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<String> callGenerate(String model, String prompt, LlmGenerateOptions options) throws Exception {
        String endpoint = llmProperties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", false);
        String reasoningEffort = resolveReasoningEffort(options);
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (llmProperties.getApiKey() != null && !llmProperties.getApiKey().isBlank()) {
            headers.setBearerAuth(llmProperties.getApiKey());
        }

        ResponseEntity<String> response = restTemplate.exchange(
                endpoint, HttpMethod.POST, new HttpEntity<>(body, headers), String.class
        );
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode textNode = root.path("choices").path(0).path("message").path("content");
        if (textNode.isTextual() && !textNode.asText().isBlank()) {
            return Optional.of(textNode.asText());
        }
        if (textNode.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : textNode) {
                if ("text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText(""));
                }
            }
            if (!text.toString().isBlank()) return Optional.of(text.toString());
        }
        return Optional.empty();
    }

    private String resolveReasoningEffort(LlmGenerateOptions options) {
        String value = options == null ? null : options.think();
        if (value == null || value.isBlank()) {
            value = llmProperties.getThink();
        }
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized)) {
            return "medium";
        }
        if ("false".equals(normalized)) {
            return "none";
        }
        if (!List.of("none", "minimal", "low", "medium", "high", "xhigh").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported reasoning effort: " + normalized);
        }
        return normalized;
    }

    private void sleepBackoff(int attempt) {
        int seconds = Math.min(1 << attempt, llmProperties.getMaxBackoffSeconds());
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
