package com.planb.emotionfriend.api.learning;

import static com.planb.emotionfriend.api.learning.LearningContracts.*;
import com.planb.emotionfriend.api.common.security.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@SecurityRequirement(name="adultBearer")
@Tag(name="콘텐츠 검토·학습 기록")
public class LearningController {
    private final LearningService learning;
    private final CurrentActor actors;
    private final Clock clock;
    public LearningController(LearningService learning,CurrentActor actors,Clock clock) {
        this.learning=learning;this.actors=actors;this.clock=clock;
    }
    private ActorPrincipal actor() { return actors.require(ActorPrincipal.Kind.ADULT); }
    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    @GetMapping("/api/v1/children/{childId}/learning-material-summaries")
    @Operation(summary="생성된 주제·요약 조회",description="기본은 미확인·보류 목록. CONFIRMED 필터로 확인 완료도 조회합니다. 원고·음성·내부 검수 정보는 반환하지 않습니다.")
    public ResponseEntity<Page<SummaryItem>> summaries(@PathVariable UUID childId,
            @RequestParam(required=false) MaterialStatus status,@RequestParam(required=false) UUID cursor,
            @RequestParam(defaultValue="20") int limit) {
        return ok(learning.summaries(actor(),childId,status,cursor,limit));
    }
    @PostMapping("/api/v1/children/{childId}/learning-material-confirmations")
    @Operation(summary="주제·요약 확인 또는 보류",description="연결된 보호자·치료사가 1~5개를 함께 처리합니다. 요약 확인만으로 발행되지 않습니다.")
    @ApiResponse(responseCode="200",content=@Content(mediaType="application/json",schema=@Schema(implementation=SummaryPage.class)))
    public ResponseEntity<byte[]> confirm(@PathVariable UUID childId,@Valid @RequestBody ConfirmationRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return learning.confirm(actor(),childId,body,key).toResponseEntity();
    }
    @GetMapping("/api/v1/children/{childId}/learning-sessions")
    @Operation(summary="학습 세션 목록·완료 상태 조회")
    public ResponseEntity<Page<SessionItem>> sessions(@PathVariable UUID childId,
            @RequestParam(required=false) SessionStatus status,@RequestParam(required=false) UUID cursor,
            @RequestParam(defaultValue="20") int limit) {
        return ok(learning.sessions(actor(),childId,status,cursor,limit));
    }
    @PostMapping("/api/v1/children/{childId}/learning-sessions")
    @Operation(summary="발행된 Day 1 학습 시작·복원",description="현재는 관리 보호자의 성인 인증 adapter입니다. 아동 기기 credential과 별도입니다. 신뢰된 발행 연결이 없으면 MATERIAL_NOT_READY입니다.")
    @ApiResponse(responseCode="201",content=@Content(mediaType="application/json",schema=@Schema(implementation=Session.class)))
    @ApiResponse(responseCode="200",content=@Content(mediaType="application/json",schema=@Schema(implementation=Session.class)))
    public ResponseEntity<byte[]> start(@PathVariable UUID childId,@Valid @RequestBody SessionStart body,
            @RequestHeader("Idempotency-Key") String key) {
        return learning.start(actor(),childId,body,key).toResponseEntity();
    }
    @GetMapping("/api/v1/learning-sessions/{sessionId}")
    @Operation(summary="세션 완료·별·슬롯별 receipt 상태 조회")
    public ResponseEntity<Session> session(@PathVariable UUID sessionId) { return ok(learning.session(actor(),sessionId)); }
    @PostMapping("/api/v1/learning-sessions/{sessionId}/slots/{slot}/confirmed-expressions")
    @Operation(summary="직접 입력의 명시적 확정",description="15분 유효 참조만 발급합니다. 발화 원문을 DB·로그·응답에 남기지 않으며 이 요청은 완료나 별을 만들지 않습니다.")
    @ApiResponse(responseCode="201",content=@Content(mediaType="application/json",schema=@Schema(implementation=Expression.class)))
    public ResponseEntity<byte[]> expression(@PathVariable UUID sessionId,@PathVariable int slot,
            @Valid @RequestBody ExpressionRequest body,@RequestHeader("Idempotency-Key") String key) {
        return learning.expression(actor(),sessionId,slot,body,key).toResponseEntity();
    }
    @PostMapping("/api/v1/learning-sessions/{sessionId}/slots/{slot}/finalize")
    @Operation(summary="슬롯 완료·별·receipt 원자 저장",description="확정 참조·검토된 경로·세션/world revision·현지 날짜를 검사합니다. 클라이언트가 완료나 별 수를 지정할 수 없습니다.")
    @ApiResponse(responseCode="200",content=@Content(mediaType="application/json",schema=@Schema(implementation=Receipt.class)))
    public ResponseEntity<byte[]> complete(@PathVariable UUID sessionId,@PathVariable int slot,
            @Valid @RequestBody FinalizeRequest body,@RequestHeader("Idempotency-Key") String key) {
        return learning.complete(actor(),sessionId,slot,body,key).toResponseEntity();
    }
    @GetMapping("/api/v1/learning-sessions/{sessionId}/slots/{slot}/receipt")
    @Operation(summary="수락된 완료 receipt 복구",description="반응·재생 실패나 보상 기한 경과 뒤에도 현재 아동 권한·동의를 검사한 후 저장된 receipt를 조회합니다.")
    public ResponseEntity<Receipt> receipt(@PathVariable UUID sessionId,@PathVariable int slot) {
        return ok(learning.receipt(actor(),sessionId,slot));
    }
    @GetMapping({"/api/v1/children/{childId}/learning-records","/api/v1/children/{childId}/learning-results"})
    @Operation(summary="아동 학습 결과·완료 기록 조회",description="receipt로 수락된 기록만 반환합니다. 원문·감정 정답률·진단 점수는 포함하지 않습니다.")
    public ResponseEntity<Page<Result>> records(@PathVariable UUID childId,@RequestParam(required=false) UUID cursor,
            @RequestParam(defaultValue="20") int limit) {
        return ok(learning.records(actor(),childId,cursor,limit));
    }
    @GetMapping("/api/v1/children/{childId}/learning-summary")
    @Operation(summary="기간별 완료·별 집계",description="UTC from 포함/to 제외, 최대 366일. 기본 최근 30일. 수집하지 않은 지표는 빈 배열과 limitations로 표시합니다.")
    public ResponseEntity<LearningSummary> summary(@PathVariable UUID childId,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) {
        Instant end=to==null?clock.instant():to;
        return ok(learning.summary(actor(),childId,from==null?end.minus(Duration.ofDays(30)):from,end));
    }
    public record SummaryPage(UUID childId,List<SummaryItem> items,UUID nextCursor) {}
}
