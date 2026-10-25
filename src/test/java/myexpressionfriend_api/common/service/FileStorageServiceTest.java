package myexpressionfriend_api.common.service;

import myexpressionfriend_api.common.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileStorageServiceTest {
    @TempDir Path directory;
    private StorageProperties properties;
    private S3ObjectStorage remote;
    private FileStorageService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new StorageProperties();
        properties.setBasePath(directory.resolve("uploads").toString());
        remote = mock(S3ObjectStorage.class);
        ObjectProvider<S3ObjectStorage> provider = mock(ObjectProvider.class);
        lenient().when(provider.getObject()).thenReturn(remote);
        service = new FileStorageService(properties, provider);
    }

    @Test
    void localStorageStillSavesAndDeletesFiles() throws Exception {
        service.init();
        String key = service.saveFile(new MockMultipartFile("file", "photo.JPG", "image/jpeg",
                new byte[]{1, 2, 3}), "profiles/child");
        assertThat(Files.readAllBytes(service.getFilePath(key))).containsExactly(1, 2, 3);
        assertThat(service.toPublicUrl(key)).startsWith("/uploads/profiles/child/").endsWith(".jpg");
        service.deleteFile(key);
        assertThat(directory.resolve("uploads").resolve(key)).doesNotExist();
        verifyNoInteractions(remote);
    }

    @Test
    void remoteStorageStreamsUploadAndKeepsStableDatabaseUrl() throws Exception {
        properties.setType("s3");
        service.init();
        doAnswer(invocation -> {
            assertThat(((InputStream) invocation.getArgument(1)).readAllBytes()).containsExactly(1, 2, 3);
            return null;
        }).when(remote).put(anyString(), any(InputStream.class), eq(3L), eq("video/mp4"));
        String key = service.saveFile(new MockMultipartFile("file", "clip.mp4", "video/mp4",
                new byte[]{1, 2, 3}), "videos/child");
        assertThat(service.toPublicUrl(key)).isEqualTo("/uploads/" + key);
        assertThat(directory.resolve("uploads")).doesNotExist();
        verify(remote).put(eq(key), any(InputStream.class), eq(3L), eq("video/mp4"));
        when(remote.signedDownloadUrl(key)).thenReturn("https://storage.example/first", "https://storage.example/second");
        assertThat(service.signedDownloadUrl(key)).endsWith("first");
        assertThat(service.signedDownloadUrl(key)).endsWith("second");
        service.deleteFile(key);
        verify(remote).delete(key);
    }

    @Test
    void rejectsTraversalAndExternalUrlsBeforeContactingStorage() {
        properties.setType("s3");
        for (String key : new String[]{"../secret", "profiles/../../secret", "/root", "C:\\secret",
                "https://other.example/file", "profiles/%2e%2e/secret", "profiles//secret"}) {
            assertThatThrownBy(() -> service.deleteFile(key)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> service.signedDownloadUrl(key)).isInstanceOf(SecurityException.class);
        }
        verifyNoInteractions(remote);
    }

    @Test
    void jsonBackupsUseRemoteStorage() {
        properties.setType("s3");
        String key = service.saveBytes("[]".getBytes(), "exports/scenarios", "batch.json", "application/json");
        assertThat(key).isEqualTo("exports/scenarios/batch.json");
        verify(remote).put(eq(key), any(InputStream.class), eq(2L), eq("application/json"));
    }
}
