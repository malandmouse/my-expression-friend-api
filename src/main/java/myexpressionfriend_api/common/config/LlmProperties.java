package myexpressionfriend_api.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm")
@Getter
@Setter
public class LlmProperties {
    private boolean enabled = false;
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey;
    private String modelFlash;
    private String modelPro;
    private String think;
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 120;
    private int maxRetries = 2;
    private int maxBackoffSeconds = 64;

    // 2-6 v2.3 운영 안전장치
    private int maxZeroScoreTurnsPerChild = 150;
    private int manualRefreshCooldownHours = 24;
}
