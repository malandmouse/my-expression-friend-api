package myexpressionfriend_api.common.config;

import myexpressionfriend_api.common.service.S3ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(StorageProperties.class, ObjectStorageConfig.class, S3ObjectStorage.class);

    @Test
    void localStorageDoesNotRequireCloudCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(S3Client.class);
        });
    }

    @Test
    void signsPrivateR2DownloadsWithoutContactingTheNetwork() {
        runner.withPropertyValues("app.storage.type=s3",
                        "app.storage.endpoint=https://account.r2.cloudflarestorage.com",
                        "app.storage.region=auto", "app.storage.bucket=test-bucket",
                        "app.storage.access-key-id=test-access-key", "app.storage.secret-access-key=test-secret-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    String url = context.getBean(S3ObjectStorage.class)
                            .signedDownloadUrl("profiles/child/photo.jpg");
                    assertThat(url).startsWith("https://account.r2.cloudflarestorage.com/test-bucket/profiles/child/photo.jpg?")
                            .contains("X-Amz-Expires=300", "X-Amz-Signature=", "auto");
                });
    }

    @Test
    void missingCredentialsFailAtStartupInsteadOfFallingBackToEphemeralDisk() {
        runner.withPropertyValues("app.storage.type=s3").run(context ->
                assertThat(context).hasFailed());
    }
}
