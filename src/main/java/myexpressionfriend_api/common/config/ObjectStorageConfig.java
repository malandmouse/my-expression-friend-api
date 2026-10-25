package myexpressionfriend_api.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class ObjectStorageConfig {
    @Bean
    public S3Configuration s3Configuration(StorageProperties properties) {
        Assert.hasText(properties.getBucket(), "S3_BUCKET must be configured.");
        Assert.hasText(properties.getAccessKeyId(), "S3_ACCESS_KEY_ID must be configured.");
        Assert.hasText(properties.getSecretAccessKey(), "S3_SECRET_ACCESS_KEY must be configured.");
        Assert.isTrue(properties.getSignedUrlDurationSeconds() > 0
                && properties.getSignedUrlDurationSeconds() <= 604800,
                "S3 signed URL duration must be between 1 and 604800 seconds.");
        return S3Configuration.builder().pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false).build();
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties properties, S3Configuration configuration) {
        var builder = S3Client.builder()
                .credentialsProvider(credentials(properties))
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(configuration)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(60)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMinutes(2)).build());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(StorageProperties properties, S3Configuration configuration) {
        var builder = S3Presigner.builder().credentialsProvider(credentials(properties))
                .region(Region.of(properties.getRegion())).serviceConfiguration(configuration);
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    private StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.getAccessKeyId(), properties.getSecretAccessKey()));
    }
}
