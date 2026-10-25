package myexpressionfriend_api.child.service;

import myexpressionfriend_api.child.domain.Child;
import myexpressionfriend_api.child.domain.ChildPermissionType;
import myexpressionfriend_api.child.repository.ChildRepository;
import myexpressionfriend_api.common.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChildProfileImageStorageTest {
    @Mock ChildRepository repository;
    @Mock FileStorageService storage;
    @InjectMocks ChildService service;

    @Test
    void failedRemoteUploadDoesNotDeleteTheExistingProfileImage() {
        UUID childId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Child child = mock(Child.class);
        when(repository.findByIdWithAuthorizedUsers(childId)).thenReturn(Optional.of(child));
        when(child.hasPermission(userId, ChildPermissionType.MANAGE)).thenReturn(true);
        when(storage.saveFile(any(), anyString())).thenThrow(new IllegalStateException("Upload failed"));

        assertThatThrownBy(() -> service.uploadProfileImage(childId, userId,
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1})))
                .isInstanceOf(IllegalStateException.class);

        verify(storage, never()).deleteFile(anyString());
        verify(child, never()).changeProfileImageUrl(anyString());
    }
}
