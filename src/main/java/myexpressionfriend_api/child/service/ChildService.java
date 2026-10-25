package myexpressionfriend_api.child.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import myexpressionfriend_api.auth.domain.user.User;
import myexpressionfriend_api.auth.domain.user.UserRole;
import myexpressionfriend_api.auth.repository.UserRepository;
import myexpressionfriend_api.child.domain.Child;
import myexpressionfriend_api.child.domain.ChildPermissionType;
import myexpressionfriend_api.child.domain.ChildrenAuthorizedUser;
import myexpressionfriend_api.child.dto.ChildCreateDTO;
import myexpressionfriend_api.child.dto.ChildProfileUpdateDTO;
import myexpressionfriend_api.child.dto.ChildResponseDTO;
import myexpressionfriend_api.child.dto.ChildUpdateDTO;
import myexpressionfriend_api.child.dto.PinUpdateDTO;
import myexpressionfriend_api.child.dto.PinVerificationDTO;
import myexpressionfriend_api.child.dto.TransferPrimaryParentDTO;
import myexpressionfriend_api.common.exception.PinLockedException;
import myexpressionfriend_api.child.repository.ChildRepository;
import myexpressionfriend_api.common.exception.EntityNotFoundException;
import myexpressionfriend_api.common.exception.InvalidRequestException;
import myexpressionfriend_api.common.service.FileStorageService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 아동 생성/수정/삭제 서비스
 *
 * <p>조회는 ChildQueryService에서 담당</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ChildService {

    private final ChildRepository childRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;

    /**
     * 아동 등록
     * - 생성자가 자동으로 주보호자(isPrimary=true, 전체 권한)가 됨
     * - 주보호자는 반드시 PARENT 역할이어야 함
     */
    @Transactional
    public ChildResponseDTO createChild(ChildCreateDTO createDTO, UUID creatorUserId) {
        log.info("아동 생성 - creatorUserId: {}", creatorUserId);

        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        if (!creator.hasRole(UserRole.PARENT)) {
            throw new InvalidRequestException("아동을 등록하려면 PARENT 역할이 필요합니다.");
        }

        Child child = Child.builder()
                .name(createDTO.getName())
                .birthDate(createDTO.getBirthDate())
                .gender(createDTO.getGender())
                .diagnosisDate(createDTO.getDiagnosisDate())
                .diagnosisInfo(createDTO.getDiagnosisInfo())
                .specialNotes(createDTO.getSpecialNotes())
                .languageSkill(createDTO.getLanguageSkill())
                .sensoryProcessing(createDTO.getSensoryProcessing())
                .interests(createDTO.getInterests())
                .profileImageUrl(createDTO.getProfileImageUrl())
                .build();

        if (createDTO.getPin() != null && !createDTO.getPin().isBlank()) {
            child.setPinCode(passwordEncoder.encode(createDTO.getPin()));
        }

        if (createDTO.getPreferredExpressions() != null) {
            child.updatePreferredExpressions(createDTO.getPreferredExpressions());
        }

        if (createDTO.getDifficultExpressions() != null) {
            child.updateDifficultExpressions(createDTO.getDifficultExpressions());
        }

        ChildrenAuthorizedUser primaryAuthorization = ChildrenAuthorizedUser.builder()
                .child(child)
                .user(creator)
                .isPrimary(true)
                .authorizedBy(creator)
                .isActive(true)
                .build();
        primaryAuthorization.setAllPermissions();

        child.addAuthorizedUser(primaryAuthorization);

        Child savedChild = childRepository.save(child);

        log.info("아동 생성 완료 - childId: {}", savedChild.getChildId());
        return ChildResponseDTO.from(savedChild, creatorUserId);
    }

    /**
     * 아동 정보 수정
     * - MANAGE 권한 보유자만 수정 가능
     */
    @Transactional
    public ChildResponseDTO updateChild(UUID childId, ChildUpdateDTO updateDTO, UUID requestUserId) {
        log.info("아동 수정 - childId: {}, requestUserId: {}", childId, requestUserId);

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.hasPermission(requestUserId, ChildPermissionType.MANAGE)) {
            throw new InvalidRequestException("아동 정보를 수정할 권한이 없습니다.");
        }

        if (updateDTO.getName() != null)                   child.changeName(updateDTO.getName());
        if (updateDTO.getBirthDate() != null)              child.changeBirthDate(updateDTO.getBirthDate());
        if (updateDTO.getGender() != null)                 child.changeGender(updateDTO.getGender());
        if (updateDTO.getDiagnosisDate() != null)          child.changeDiagnosisDate(updateDTO.getDiagnosisDate());
        if (updateDTO.getDiagnosisInfo() != null)          child.changeDiagnosisInfo(updateDTO.getDiagnosisInfo());
        if (updateDTO.getSpecialNotes() != null)           child.changeSpecialNotes(updateDTO.getSpecialNotes());
        if (updateDTO.getPreferredExpressions() != null)   child.updatePreferredExpressions(updateDTO.getPreferredExpressions());
        if (updateDTO.getDifficultExpressions() != null)   child.updateDifficultExpressions(updateDTO.getDifficultExpressions());
        if (updateDTO.getLanguageSkill() != null)          child.changeLanguageSkill(updateDTO.getLanguageSkill());
        if (updateDTO.getSensoryProcessing() != null)      child.changeSensoryProcessing(updateDTO.getSensoryProcessing());
        if (updateDTO.getInterests() != null)              child.changeInterests(updateDTO.getInterests());
        if (updateDTO.getProfileImageUrl() != null)        child.changeProfileImageUrl(updateDTO.getProfileImageUrl());

        log.info("아동 수정 완료 - childId: {}", childId);
        return ChildResponseDTO.from(child, requestUserId);
    }

    /**
     * 아동 프로필 부분 수정 (진단 정보 제외)
     * - MANAGE 권한 보유자만 수정 가능
     */
    @Transactional
    public ChildResponseDTO updateChildProfile(UUID childId, ChildProfileUpdateDTO updateDTO, UUID requestUserId) {
        log.info("아동 프로필 수정 - childId: {}, requestUserId: {}", childId, requestUserId);

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.hasPermission(requestUserId, ChildPermissionType.MANAGE)) {
            throw new InvalidRequestException("아동 프로필을 수정할 권한이 없습니다.");
        }

        if (updateDTO.getName() != null)            child.changeName(updateDTO.getName());
        if (updateDTO.getBirthDate() != null)        child.changeBirthDate(updateDTO.getBirthDate());
        if (updateDTO.getGender() != null)           child.changeGender(updateDTO.getGender());
        if (updateDTO.getInterests() != null)        child.changeInterests(updateDTO.getInterests());
        if (updateDTO.getProfileImageUrl() != null)  child.changeProfileImageUrl(updateDTO.getProfileImageUrl());

        log.info("아동 프로필 수정 완료 - childId: {}", childId);
        return ChildResponseDTO.from(child, requestUserId);
    }

    /**
     * PIN 설정 또는 변경
     * - 주보호자만 가능
     * - 기존 PIN이 있으면 currentPin 검증 후 변경
     */
    @Transactional
    public void updatePin(UUID childId, UUID userId, PinUpdateDTO dto) {
        log.info("PIN 변경 - childId: {}, userId: {}", childId, userId);

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.isPrimaryParent(userId)) {
            throw new InvalidRequestException("주보호자만 PIN을 변경할 수 있습니다.");
        }

        if (Boolean.TRUE.equals(child.getPinEnabled())) {
            if (dto.getCurrentPin() == null) {
                throw new InvalidRequestException("기존 PIN이 설정되어 있습니다. 현재 PIN을 입력해주세요.");
            }
            boolean verified = child.verifyPin(dto.getCurrentPin(), passwordEncoder);
            if (!verified) {
                throw new InvalidRequestException("현재 PIN이 올바르지 않습니다.");
            }
        }

        child.setPinCode(passwordEncoder.encode(dto.getNewPin()));
        log.info("PIN 변경 완료 - childId: {}", childId);
    }

    /**
     * PIN 검증
     * - 접근 권한 보유자면 가능
     */
    @Transactional
    public boolean verifyPin(UUID childId, UUID userId, PinVerificationDTO dto) {
        log.info("PIN 검증 - childId: {}, userId: {}", childId, userId);

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.canAccess(userId)) {
            throw new InvalidRequestException("해당 아동에 대한 접근 권한이 없습니다.");
        }

        return child.verifyPin(dto.getPin(), passwordEncoder);
    }

    /**
     * PIN 제거
     * - 주보호자만 가능, 현재 PIN 검증 필수
     */
    @Transactional
    public void removePin(UUID childId, UUID userId, String currentPin) {
        log.info("PIN 제거 - childId: {}, userId: {}", childId, userId);

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.isPrimaryParent(userId)) {
            throw new InvalidRequestException("주보호자만 PIN을 제거할 수 있습니다.");
        }

        if (Boolean.TRUE.equals(child.getPinEnabled())) {
            boolean verified = child.verifyPin(currentPin, passwordEncoder);
            if (!verified) {
                throw new InvalidRequestException("PIN이 올바르지 않습니다.");
            }
        }

        child.removePinCode();
        log.info("PIN 제거 완료 - childId: {}", childId);
    }

    /**
     * 주보호자 권한 이전
     * - 현재 주보호자만 가능
     * - 대상자는 이미 권한 사용자 목록에 있어야 함
     */
    @Transactional
    public void transferPrimaryParent(UUID childId, UUID userId, TransferPrimaryParentDTO dto) {
        log.info("주보호자 이전 - childId: {}, userId: {}, newPrimaryId: {}", childId, userId, dto.getNewPrimaryUserId());

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.isPrimaryParent(userId)) {
            throw new InvalidRequestException("주보호자만 권한을 이전할 수 있습니다.");
        }

        child.transferPrimaryParent(dto.getNewPrimaryUserId());
        log.info("주보호자 이전 완료 - childId: {}, newPrimaryId: {}", childId, dto.getNewPrimaryUserId());
    }

    /**
     * 아동 삭제 (Soft Delete)
     * - 주보호자만 삭제 가능
     */
    @Transactional
    public void deleteChild(UUID childId, UUID requestUserId) {
        log.info("아동 삭제 - childId: {}, requestUserId: {}", childId, requestUserId);

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.isPrimaryParent(requestUserId)) {
            throw new InvalidRequestException("주보호자만 아동을 삭제할 수 있습니다.");
        }

        child.delete(requestUserId);
        log.info("아동 삭제 완료 - childId: {}", childId);
    }

    /**
     * 아동 프로필 이미지 업로드
     * - MANAGE 권한 보유자만 가능
     * - 기존 이미지가 있으면 파일 시스템에서 삭제 후 교체
     */
    @Transactional
    public ChildResponseDTO uploadProfileImage(UUID childId, UUID requestUserId, MultipartFile file) {
        log.info("프로필 이미지 업로드 - childId: {}, requestUserId: {}", childId, requestUserId);

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.hasPermission(requestUserId, ChildPermissionType.MANAGE)) {
            throw new InvalidRequestException("프로필 이미지를 변경할 권한이 없습니다.");
        }

        // 새 업로드가 실패했을 때 기존 파일이 사라지지 않도록 먼저 저장한다.
        String relativePath = fileStorageService.saveFile(file, "profiles/" + childId);
        String publicUrl = fileStorageService.toPublicUrl(relativePath);

        deleteStoredImage(child.getProfileImageUrl());
        child.changeProfileImageUrl(publicUrl);

        log.info("프로필 이미지 업로드 완료 - childId: {}", childId);
        return ChildResponseDTO.from(child, requestUserId);
    }

    /**
     * 아동 프로필 이미지 삭제
     * - MANAGE 권한 보유자만 가능
     */
    @Transactional
    public ChildResponseDTO deleteProfileImage(UUID childId, UUID requestUserId) {
        log.info("프로필 이미지 삭제 - childId: {}, requestUserId: {}", childId, requestUserId);

        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동을 찾을 수 없습니다."));

        if (!child.hasPermission(requestUserId, ChildPermissionType.MANAGE)) {
            throw new InvalidRequestException("프로필 이미지를 삭제할 권한이 없습니다.");
        }

        deleteStoredImage(child.getProfileImageUrl());
        child.changeProfileImageUrl(null);

        log.info("프로필 이미지 삭제 완료 - childId: {}", childId);
        return ChildResponseDTO.from(child, requestUserId);
    }

    // ---- 내부 헬퍼 ----

    /**
     * publicUrl(/uploads/...)에서 상대경로를 추출하여 파일을 삭제한다.
     */
    private void deleteStoredImage(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return;
        }
        // 이 서버가 발급한 파일 주소만 삭제한다. 기존 외부 S3 URL은 유지한다.
        if (publicUrl.contains("://")) {
            return;
        }
        String relativePath = publicUrl.startsWith("/uploads/")
                ? publicUrl.substring("/uploads/".length())
                : publicUrl;
        fileStorageService.deleteFile(relativePath);
    }
}
