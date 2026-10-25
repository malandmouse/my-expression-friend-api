package myexpressionfriend_api.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.storage")
@Getter
@Setter
public class StorageProperties {
    private String type = "local";
    private String basePath = "uploads";
    private String endpoint;
    private String region = "auto";
    private String bucket;
    private String accessKeyId;
    private String secretAccessKey;
    private long signedUrlDurationSeconds = 300;
}
