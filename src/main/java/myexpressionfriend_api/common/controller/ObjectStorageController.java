package myexpressionfriend_api.common.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import myexpressionfriend_api.common.service.FileStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class ObjectStorageController {
    private final FileStorageService fileStorageService;

    @GetMapping("/uploads/**")
    public ResponseEntity<Void> download(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/uploads/";
        String key = UriUtils.decode(request.getRequestURI().substring(prefix.length()), StandardCharsets.UTF_8);
        try {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(fileStorageService.signedDownloadUrl(key)))
                    .cacheControl(CacheControl.noStore()).build();
        } catch (SecurityException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file path.");
        }
    }
}
