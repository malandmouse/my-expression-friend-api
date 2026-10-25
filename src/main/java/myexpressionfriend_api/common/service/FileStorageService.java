package myexpressionfriend_api.common.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import myexpressionfriend_api.common.config.StorageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 공통 파일 저장 서비스.
 * <p>
 * 파일은 설정에 따라 로컬 {@code {basePath}/{relativePath}} 또는 R2/S3에 저장되며,
 * 공개 URL은 {@code /uploads/{relativePath}} 형식을 따른다.
 * <p>
 * 디렉터리 구조 예시: {@code uploads/profiles/{userId}/{uuid}.jpg}
 * 공개 URL 예시:   {@code /uploads/profiles/{userId}/{uuid}.jpg}
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileStorageService {

    private final StorageProperties properties;
    private final ObjectProvider<S3ObjectStorage> objectStorageProvider;

    public boolean isObjectStorage() {
        return "s3".equals(properties.getType());
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PostConstruct
    public void init() {
        if (!isObjectStorage() && !"local".equals(properties.getType())) {
            throw new IllegalArgumentException("APP_STORAGE_TYPE must be local or s3.");
        }
        if (isObjectStorage()) return;
        try {
            Files.createDirectories(Paths.get(properties.getBasePath()));
            log.info("Storage directory initialized: {}", properties.getBasePath());
        } catch (IOException e) {
            throw new RuntimeException("파일 저장소 초기화에 실패했습니다.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * 파일을 저장하고 상대 경로(relativePath)를 반환한다.
     *
     * @param file         업로드된 파일
     * @param subdirectory 저장 위치 ({@code profiles/550e8400-...} 형식)
     * @return 저장된 파일의 상대 경로 ({@code subdirectory/uuid.ext})
     */
    public String saveFile(MultipartFile file, String subdirectory) {
        validateFile(file);

        String storedFileName = generateStoredFileName(file.getOriginalFilename());
        String relativePath = subdirectory + "/" + storedFileName;

        try (InputStream input = file.getInputStream()) {
            store(relativePath, input, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 중 오류가 발생했습니다.", e);
        }

        log.debug("File saved: {}", relativePath);
        return relativePath;
    }

    public String saveBytes(byte[] content, String subdirectory, String filename, String contentType) {
        String relativePath = subdirectory + "/" + filename;
        try (InputStream input = new ByteArrayInputStream(content)) {
            store(relativePath, input, content.length, contentType);
            return relativePath;
        } catch (IOException ex) {
            throw new IllegalStateException("파일 저장 중 오류가 발생했습니다.", ex);
        }
    }

    private void store(String relativePath, InputStream input, long length, String contentType) throws IOException {
        validateKey(relativePath);
        if (isObjectStorage()) {
            objectStorageProvider.getObject().put(relativePath, input, length,
                    contentType == null ? "application/octet-stream" : contentType);
        } else {
            Path filePath = resolveAndValidate(relativePath);
            Files.createDirectories(filePath.getParent());
            Files.copy(input, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 상대 경로의 파일을 삭제한다. 파일이 없으면 무시한다.
     *
     * @param relativePath {@link #saveFile}이 반환한 상대 경로
     */
    public void deleteFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        validateKey(relativePath);
        if (isObjectStorage()) {
            objectStorageProvider.getObject().delete(relativePath);
            return;
        }
        try {
            Path filePath = resolveAndValidate(relativePath);
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.debug("File deleted: {}", relativePath);
            }
        } catch (IOException e) {
            log.error("파일 삭제 실패 - path: {}", relativePath, e);
        }
    }

    /**
     * 상대 경로를 공개 URL로 변환한다.
     *
     * @param relativePath {@link #saveFile}이 반환한 상대 경로
     * @return {@code /uploads/{relativePath}}
     */
    public String toPublicUrl(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        validateKey(relativePath);
        return "/uploads/" + UriUtils.encodePath(relativePath, StandardCharsets.UTF_8);
    }

    public String signedDownloadUrl(String relativePath) {
        validateKey(relativePath);
        if (!isObjectStorage()) throw new IllegalStateException("Object storage is not enabled.");
        return objectStorageProvider.getObject().signedDownloadUrl(relativePath);
    }

    /**
     * 상대 경로에 해당하는 실제 {@link Path}를 반환한다.
     */
    public Path getFilePath(String relativePath) {
        if (isObjectStorage()) {
            throw new IllegalStateException("Object storage files do not have a local filesystem path.");
        }
        Path filePath = resolveAndValidate(relativePath);
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("파일을 찾을 수 없습니다: " + relativePath);
        }
        return filePath;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("파일은 필수입니다.");
        }
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new IllegalArgumentException("파일명이 유효하지 않습니다.");
        }
    }

    /**
     * UUID 기반 고유 파일명 생성: {@code {uuid}.{extension}}
     */
    private String generateStoredFileName(String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf(".")).toLowerCase();
            if (!extension.matches("\\.[a-z0-9]{1,10}")) extension = "";
        }
        return UUID.randomUUID() + extension;
    }

    /**
     * 상대 경로를 절대 경로로 변환하고, 경로 탈출(Path Traversal) 공격을 방지한다.
     */
    private Path resolveAndValidate(String relativePath) {
        validateKey(relativePath);
        Path base = Paths.get(properties.getBasePath()).toAbsolutePath().normalize();
        Path target = base.resolve(relativePath).normalize();
        if (!target.startsWith(base)) {
            throw new SecurityException("잘못된 파일 경로입니다.");
        }
        return target;
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")
                || key.contains("\\") || key.contains("%") || key.contains("?") || key.contains("#")
                || key.contains(":") || key.chars().anyMatch(Character::isISOControl)) {
            throw new SecurityException("잘못된 파일 경로입니다.");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new SecurityException("잘못된 파일 경로입니다.");
            }
        }
    }
}
