package myexpressionfriend_api.common.service;

import lombok.RequiredArgsConstructor;
import myexpressionfriend_api.common.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3ObjectStorage {
    private final S3Client client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public void put(String key, InputStream content, long length, String contentType) {
        client.putObject(PutObjectRequest.builder().bucket(properties.getBucket()).key(key)
                .contentType(contentType).build(), RequestBody.fromInputStream(content, length));
    }

    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
    }

    public String signedDownloadUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getSignedUrlDurationSeconds()))
                .getObjectRequest(request -> request.bucket(properties.getBucket()).key(key))
                .build()).url().toString();
    }
}
