package com.planb.emotionfriend.api.membership;

import static com.planb.emotionfriend.api.membership.MembershipContracts.*;

import com.planb.emotionfriend.api.auth.AuthContracts.Status;
import com.planb.emotionfriend.api.auth.AuthRateLimiter;
import com.planb.emotionfriend.api.common.security.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;

@RestController
@SecurityRequirement(name = "adultBearer")
@Tag(name = "회원·역할 관리")
public class MembershipController {
    private final MembershipService service;
    private final CurrentActor actors;
    private final AuthRateLimiter limits;

    public MembershipController(
            MembershipService service, CurrentActor actors, AuthRateLimiter limits) {
        this.service = service;
        this.actors = actors;
        this.limits = limits;
    }

    private ActorPrincipal actor() {
        return actors.require(ActorPrincipal.Kind.ADULT);
    }

    @PostMapping("/api/v1/auth/reauthenticate")
    @Operation(
            summary = "민감 작업 전 비밀번호 재인증",
            description = "5분 동안 현재 세션에서만 유효한 verificationRef를 발급합니다.")
    public ResponseEntity<Reauthentication> reauthenticate(
            @Valid @RequestBody PasswordRequest body) {
        var a = actor();
        limits.consume("reauth", a.actorId(), 10, Duration.ofMinutes(15));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.reauthenticate(a, body.password()));
    }

    @PostMapping("/api/v1/auth/email-verifications")
    @ApiResponse(
            responseCode = "202",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = Accepted.class)))
    @Operation(
            summary = "이메일 확인 요청",
            description = "15분 유효한 확인 정보를 발송 대기함에 저장합니다. EF_MAIL_ENABLED=true이면 Resend 전송 작업이 처리합니다. 202는 접수 응답이며 수신함 도달을 보증하지 않습니다.")
    public ResponseEntity<Accepted> requestEmail() {
        var a = actor();
        limits.consume("email-issue", a.actorId(), 3, Duration.ofMinutes(15));
        service.requestEmail(a);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(Accepted.generic());
    }

    @PostMapping("/api/v1/auth/email-verifications/confirm")
    @ApiResponse(responseCode = "204", description = "이메일 확인 완료", content = @Content)
    @Operation(summary = "이메일 소유 확인", description = "로그인 계정과 결속된 일회용 토큰을 확인합니다.")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest body) {
        var a = actor();
        limits.consume("email-confirm", a.actorId(), 10, Duration.ofMinutes(15));
        service.verifyEmail(a, body);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/v1/admin/members")
    @Operation(summary = "운영자의 회원 목록 조회")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) UUID cursor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.members(actor(), status, limit, cursor));
    }

    @PostMapping("/api/v1/admin/members/{memberId}/approve")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation =
                                                    com.planb.emotionfriend.api.auth.AuthContracts
                                                            .MemberView.class)))
    @Operation(
            summary = "신청 역할로 회원 승인",
            description = "이메일 확인, 최근 재인증 및 검토 근거가 필요합니다. 해당 회원은 다시 로그인해야 합니다.")
    public ResponseEntity<byte[]> approve(
            @PathVariable UUID memberId,
            @Valid @RequestBody ReviewRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return service.review(actor(), memberId, "approve", body, key).toResponseEntity();
    }

    @PostMapping("/api/v1/admin/members/{memberId}/reject")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation =
                                                    com.planb.emotionfriend.api.auth.AuthContracts
                                                            .MemberView.class)))
    @Operation(summary = "가입 신청 반려")
    public ResponseEntity<byte[]> reject(
            @PathVariable UUID memberId,
            @Valid @RequestBody ReviewRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return service.review(actor(), memberId, "reject", body, key).toResponseEntity();
    }

    @PostMapping("/api/v1/admin/members/{memberId}/suspend")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation =
                                                    com.planb.emotionfriend.api.auth.AuthContracts
                                                            .MemberView.class)))
    @Operation(summary = "회원 정지 및 세션·연결 철회")
    public ResponseEntity<byte[]> suspend(
            @PathVariable UUID memberId,
            @Valid @RequestBody ReviewRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return service.review(actor(), memberId, "suspend", body, key).toResponseEntity();
    }

    @PostMapping("/api/v1/admin/members/{memberId}/role")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation =
                                                    com.planb.emotionfriend.api.auth.AuthContracts
                                                            .MemberView.class)))
    @Operation(
            summary = "검토를 거친 보호자·치료사 역할 변경",
            description = "관리 중인 아동이 있는 보호자는 역할을 변경할 수 없습니다. 운영자 역할은 부여할 수 없습니다.")
    public ResponseEntity<byte[]> role(
            @PathVariable UUID memberId,
            @Valid @RequestBody RoleChangeRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return service.changeRole(actor(), memberId, body, key).toResponseEntity();
    }
}
