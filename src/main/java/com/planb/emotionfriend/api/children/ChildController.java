package com.planb.emotionfriend.api.children;

import static com.planb.emotionfriend.api.children.ChildContracts.*;

import com.planb.emotionfriend.api.common.security.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@SecurityRequirement(name = "adultBearer")
@Tag(name = "아동·접근 권한·동의")
public class ChildController {
    private final ChildService children;
    private final TherapistConnections connections;
    private final CurrentActor actors;

    public ChildController(
            ChildService children, TherapistConnections connections, CurrentActor actors) {
        this.children = children;
        this.connections = connections;
        this.actors = actors;
    }

    private ActorPrincipal actor() {
        return actors.require(ActorPrincipal.Kind.ADULT);
    }

    private <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    @GetMapping("/api/v1/children")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChildResponses.Children.class)))
    @Operation(summary = "접근 가능한 아동 목록")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) UUID cursor) {
        return ok(children.list(actor(), limit, cursor));
    }

    @PostMapping("/api/v1/children")
    @ApiResponse(
            responseCode = "201",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChildResponses.ProfileVersion.class)))
    @Operation(
            summary = "보호자의 아동 등록",
            description = "관리 보호자는 로그인 계정으로 지정됩니다. 보호 관계 확인이 필요하며, 목적별 동의는 별도입니다.")
    public ResponseEntity<byte[]> create(
            @Valid @RequestBody CreateChildRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return children.create(actor(), body, key).toResponseEntity();
    }

    @GetMapping("/api/v1/children/{childId}/profile")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChildResponses.ProfileVersion.class)))
    @Operation(summary = "아동 프로필 조회")
    public ResponseEntity<Map<String, Object>> profile(@PathVariable UUID childId) {
        return ok(children.getProfile(actor(), childId));
    }

    @PutMapping("/api/v1/children/{childId}/profile")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChildResponses.ProfileVersion.class)))
    @Operation(summary = "보호자의 아동 프로필 수정")
    public ResponseEntity<byte[]> update(
            @PathVariable UUID childId,
            @Valid @RequestBody UpdateProfileRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return children.updateProfile(actor(), childId, body, key).toResponseEntity();
    }

    @DeleteMapping("/api/v1/children/{childId}")
    @ApiResponse(responseCode = "204", description = "접근 차단 및 연결 철회 완료", content = @Content)
    @Operation(summary = "아동 비활성화 및 연결 철회", description = "최근 재인증이 필요합니다. 물리 삭제가 아닌 접근 차단입니다.")
    public ResponseEntity<byte[]> delete(
            @PathVariable UUID childId, @RequestHeader("Idempotency-Key") String key) {
        return children.delete(actor(), childId, key).toResponseEntity();
    }

    @GetMapping("/api/v1/consent-notices")
    @Operation(summary = "목적별 동의 안내 버전 조회")
    public ResponseEntity<?> notices() {
        return ok(children.notices(actor()));
    }

    @GetMapping("/api/v1/children/{childId}/consents")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChildResponses.Consents.class)))
    @Operation(summary = "관리 보호자의 현재 동의 조회")
    public ResponseEntity<Map<String, Object>> consents(@PathVariable UUID childId) {
        return ok(children.consents(actor(), childId));
    }

    @PostMapping("/api/v1/children/{childId}/consents")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChildResponses.Consent.class)))
    @Operation(
            summary = "목적별 동의 부여·철회",
            description =
                    "최근 재인증으로 받은 guardianVerificationRef가 필요합니다. 기본 프로필·학습 동의 철회 시 치료사 연결과 미완료 초대를"
                            + " 함께 철회합니다.")
    public ResponseEntity<byte[]> consent(
            @PathVariable UUID childId,
            @Valid @RequestBody ConsentChangeRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return children.changeConsent(actor(), childId, body, key).toResponseEntity();
    }

    @PostMapping("/api/v1/children/{childId}/therapist-invitations")
    @ApiResponse(
            responseCode = "202",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation =
                                                    com.planb.emotionfriend.api.membership
                                                            .MembershipContracts.Accepted.class)))
    @Operation(
            summary = "치료사 연결 초대",
            description =
                    "이메일만으로 연결되지 않습니다. 최근 재인증과 기본 프로필·학습 동의가 필요합니다. 상대의 가입 여부를 공개하지 않는 202 응답입니다.")
    public ResponseEntity<byte[]> invite(
            @PathVariable UUID childId,
            @Valid @RequestBody InvitationRequest body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var a = actor();
        return connections
                .invite(a, childId, body, key, request.getRemoteAddr())
                .toResponseEntity();
    }

    @GetMapping("/api/v1/children/{childId}/therapist-invitations")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(implementation = ChildResponses.SentInvitations.class)))
    @Operation(summary = "보호자가 보낸 초대 요청 목록")
    public ResponseEntity<Map<String, Object>> sent(@PathVariable UUID childId) {
        return ok(connections.guardianInvitations(actor(), childId));
    }

    @GetMapping("/api/v1/me/therapist-invitations")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChildResponses.Inbox.class)))
    @Operation(summary = "치료사 본인에게 온 초대", description = "수락 전 아동 ID와 프로필을 제공하지 않습니다.")
    public ResponseEntity<Map<String, Object>> inbox() {
        return ok(connections.inbox(actor()));
    }

    @PostMapping("/api/v1/therapist-invitations/{invitationId}/accept")
    @ApiResponse(responseCode = "204", description = "연결 완료", content = @Content)
    @Operation(summary = "치료사의 초대 수락", description = "대상 계정 로그인·최근 재인증과 실제 치료 관계 확인이 필요합니다.")
    public ResponseEntity<byte[]> accept(
            @PathVariable UUID invitationId,
            @Valid @RequestBody AcceptInvitationRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return connections.respond(actor(), invitationId, true, body, key).toResponseEntity();
    }

    @PostMapping("/api/v1/therapist-invitations/{invitationId}/decline")
    @ApiResponse(responseCode = "204", description = "거절 완료", content = @Content)
    @Operation(summary = "치료사의 초대 거절")
    public ResponseEntity<byte[]> decline(
            @PathVariable UUID invitationId, @RequestHeader("Idempotency-Key") String key) {
        return connections.respond(actor(), invitationId, false, Map.of(), key).toResponseEntity();
    }

    @DeleteMapping("/api/v1/children/{childId}/therapist-invitations/{invitationId}")
    @ApiResponse(responseCode = "204", description = "취소 완료", content = @Content)
    @Operation(summary = "보호자의 초대 취소")
    public ResponseEntity<byte[]> cancel(
            @PathVariable UUID childId,
            @PathVariable UUID invitationId,
            @RequestHeader("Idempotency-Key") String key) {
        return connections.cancel(actor(), childId, invitationId, key).toResponseEntity();
    }

    @GetMapping("/api/v1/children/{childId}/therapists")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChildResponses.Therapists.class)))
    @Operation(summary = "보호자의 연결된 치료사 조회")
    public ResponseEntity<Map<String, Object>> therapists(@PathVariable UUID childId) {
        return ok(connections.therapists(actor(), childId));
    }

    @DeleteMapping("/api/v1/children/{childId}/therapists/{therapistId}")
    @ApiResponse(responseCode = "204", description = "연결 해제 완료", content = @Content)
    @Operation(summary = "치료사 연결 해제", description = "관리 보호자 또는 해당 치료사 본인만 가능합니다.")
    public ResponseEntity<byte[]> disconnect(
            @PathVariable UUID childId,
            @PathVariable UUID therapistId,
            @RequestHeader("Idempotency-Key") String key) {
        return connections.disconnect(actor(), childId, therapistId, key).toResponseEntity();
    }
}
