package com.planb.emotionfriend.api.auth;

import static com.planb.emotionfriend.api.auth.AuthContracts.*;

import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.common.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "회원가입·로그인", description = "이메일 가입, 승인 상태 확인 및 성인 세션 관리")
public class AuthController {
    private final AuthService auth;
    private final AuthRateLimiter limits;
    private final CurrentActor actors;
    public AuthController(AuthService auth, AuthRateLimiter limits, CurrentActor actors) {
        this.auth = auth;
        this.limits = limits;
        this.actors = actors;
    }

    @PostMapping("/api/v1/auth/signup")
    @ApiResponse(responseCode="201",content=@Content(mediaType="application/json",schema=@Schema(implementation=MemberView.class)))
    @Operation(summary = "보호자·치료사 가입 신청", description = "role=null, status=PENDING으로 생성합니다. 이메일 확인 후 운영자가 신청 역할을 승인합니다.")
    public ResponseEntity<MemberView> signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request) {
        limits.consume("signup-ip", request.getRemoteAddr(), 10, Duration.ofHours(1));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(auth.signup(body));
    }

    @PostMapping("/api/v1/auth/login")
    @Operation(summary = "이메일·비밀번호 로그인", description = "loginId에는 가입 이메일을 입력합니다. PENDING/REJECTED는 본인 정보·이메일 확인·로그아웃만 허용합니다.")
    public ResponseEntity<Credentials> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        limits.consume("login-ip", request.getRemoteAddr(), 100, Duration.ofMinutes(15));
        limits.consume("login-account", AuthService.normalizeEmail(body.loginId()), 20, Duration.ofMinutes(15));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.login(body));
    }

    @PostMapping("/api/v1/auth/refresh")
    @Operation(summary = "토큰 재발급", description = "현재 refreshToken을 한 번 사용해 새 토큰 쌍을 받습니다. 이전 토큰 재사용 시 해당 로그인 세션 전체가 폐기됩니다. Bearer 없이 호출합니다.")
    public ResponseEntity<Credentials> refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        limits.consume("refresh-ip", request.getRemoteAddr(), 120, Duration.ofMinutes(15));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.refresh(body.refreshToken()));
    }

    @PostMapping("/api/v1/auth/logout")
    @ApiResponse(responseCode="204",description="현재 로그인 세션 종료",content=@Content)
    @SecurityRequirement(name = "adultBearer")
    @Operation(summary = "현재 로그인 세션 로그아웃", description = "Bearer와 같은 세션의 refreshToken이 필요합니다. 현재 세션의 액세스·리프레시 토큰을 함께 무효화합니다.")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest body) {
        auth.logout(actors.require(ActorPrincipal.Kind.ADULT), body.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/v1/me")
    @SecurityRequirement(name = "adultBearer")
    @Operation(summary = "본인 계정·가입 심사 상태 조회")
    public ResponseEntity<MemberView> me() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.me(actors.require(ActorPrincipal.Kind.ADULT)));
    }
}
