package myexpressionfriend_api.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EmbeddingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    SpringAiRetryAutoConfiguration.class, OpenAiEmbeddingAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class, OpenAiImageAutoConfiguration.class,
                    OpenAiModerationAutoConfiguration.class, OpenAiAudioSpeechAutoConfiguration.class,
                    OpenAiAudioTranscriptionAutoConfiguration.class, PgVectorStoreAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withPropertyValues("spring.profiles.active=prod");

    @Test
    void embeddingIsDisabledWithoutCredentialsByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
            assertThat(context).doesNotHaveBean(VectorStore.class);
        });
    }

    @Test
    void usesIndependentEmbeddingUrlKeyModelAndDimensions() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://embedding.example/custom/v1/embeddings"))
                .andExpect(header("Authorization", "Bearer embedding-only-test-key"))
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.dimensions").value(1536))
                .andRespond(withSuccess("""
                        {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
                         "model":"text-embedding-3-small","usage":{"prompt_tokens":1,"total_tokens":1}}
                        """, MediaType.APPLICATION_JSON));

        runner.withBean(RestClient.Builder.class, () -> builder)
                .withPropertyValues("SPRING_AI_EMBEDDING_MODEL=openai",
                        "SPRING_AI_VECTORSTORE_TYPE=pgvector",
                        "EMBEDDING_BASE_URL=https://embedding.example/custom/v1",
                        "EMBEDDING_API_KEY=embedding-only-test-key", "LLM_API_KEY=generation-only-test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorStore.class);
                    assertThat(context.getBean(EmbeddingModel.class).embed("test")).hasSize(2);
                    assertThat(context.getEnvironment().getProperty("spring.ai.vectorstore.pgvector.dimensions"))
                            .isEqualTo("1536");
                    server.verify();
                });
    }
}
