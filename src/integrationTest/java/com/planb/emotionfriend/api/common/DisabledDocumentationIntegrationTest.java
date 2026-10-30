package com.planb.emotionfriend.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
class DisabledDocumentationIntegrationTest {
    @LocalServerPort int port;

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry registry) {
        FoundationIntegrationTest.isolatedPostgres(registry);
    }

    @Test
    void disabledDocsAreNotPublicAndHealthRemainsAvailable() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs.yaml"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(401);
            }
            var health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
        }
    }
}
