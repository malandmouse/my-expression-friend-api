package myexpressionfriend_api.homework.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import myexpressionfriend_api.auth.domain.user.User;
import myexpressionfriend_api.auth.repository.UserRepository;
import myexpressionfriend_api.child.domain.Child;
import myexpressionfriend_api.child.domain.ChildPermissionType;
import myexpressionfriend_api.child.repository.ChildRepository;
import myexpressionfriend_api.common.domain.PeersTheme;
import myexpressionfriend_api.common.exception.AuthenticationFailedException;
import myexpressionfriend_api.common.exception.EntityNotFoundException;
import myexpressionfriend_api.common.exception.InvalidRequestException;
import myexpressionfriend_api.homework.domain.HomeworkAssignment;
import myexpressionfriend_api.homework.domain.HomeworkReport;
import myexpressionfriend_api.homework.domain.HomeworkStatus;
import myexpressionfriend_api.homework.domain.StrategyFocus;
import myexpressionfriend_api.homework.domain.StrategyTipSource;
import myexpressionfriend_api.homework.dto.HomeworkAssignmentCreateRequest;
import myexpressionfriend_api.homework.dto.HomeworkAssignmentResponse;
import myexpressionfriend_api.homework.dto.HomeworkAssignmentUpdateRequest;
import myexpressionfriend_api.homework.dto.HomeworkGenerateMissionRequest;
import myexpressionfriend_api.homework.dto.HomeworkMissionSummaryResponse;
import myexpressionfriend_api.homework.dto.HomeworkReportResponse;
import myexpressionfriend_api.homework.dto.HomeworkReportSubmitRequest;
import myexpressionfriend_api.homework.dto.HomeworkReviewRequest;
import myexpressionfriend_api.homework.event.HomeworkReviewedEvent;
import myexpressionfriend_api.homework.event.HomeworkSubmittedEvent;
import myexpressionfriend_api.homework.repository.HomeworkAssignmentRepository;
import myexpressionfriend_api.homework.repository.HomeworkReportRepository;
import myexpressionfriend_api.rag.dto.RagGenerateRequest;
import myexpressionfriend_api.rag.dto.RagGenerateResponse;
import myexpressionfriend_api.rag.service.RagGenerationService;
import myexpressionfriend_api.statistics.dialogue.domain.DialogueStatSummary;
import myexpressionfriend_api.statistics.dialogue.repository.DialogueStatSummaryRepository;
import myexpressionfriend_api.statistics.dialogue.service.DialogueStatisticsService;
import myexpressionfriend_api.statistics.expression.domain.ExpressionStatSummary;
import myexpressionfriend_api.statistics.expression.repository.ExpressionStatSummaryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HomeworkService {

    private final ChildRepository childRepository;
    private final UserRepository userRepository;
    private final HomeworkAssignmentRepository homeworkAssignmentRepository;
    private final HomeworkReportRepository homeworkReportRepository;
    private final RagGenerationService ragGenerationService;
    private final DialogueStatSummaryRepository dialogueStatSummaryRepository;
    private final DialogueStatisticsService dialogueStatisticsService;
    private final ExpressionStatSummaryRepository expressionStatSummaryRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<HomeworkAssignmentResponse> getAssignments(
            UUID userId,
            UUID childId,
            HomeworkStatus status,
            Pageable pageable
    ) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.WRITE_NOTE, ChildPermissionType.ASSIGN_MISSION);

        Page<HomeworkAssignment> assignments = status == null
                ? homeworkAssignmentRepository.findByChild_ChildIdOrderByCreatedAtDesc(childId, pageable)
                : homeworkAssignmentRepository.findByChild_ChildIdAndStatusOrderByCreatedAtDesc(childId, status, pageable);

        return assignments.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public HomeworkAssignmentResponse getAssignment(UUID userId, UUID childId, UUID homeworkId) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.WRITE_NOTE, ChildPermissionType.ASSIGN_MISSION);
        return toResponse(loadHomework(childId, homeworkId));
    }

    @Transactional(readOnly = true)
    public HomeworkAssignmentResponse getCurrentAssignment(UUID userId, UUID childId) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.WRITE_NOTE, ChildPermissionType.ASSIGN_MISSION);
        return homeworkAssignmentRepository
                .findCurrentByChildAndStatus(childId, HomeworkStatus.PENDING, LocalDate.now())
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public HomeworkMissionSummaryResponse getMissionSummary(UUID userId, UUID childId) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.WRITE_NOTE, ChildPermissionType.ASSIGN_MISSION);
        return buildMissionSummary(childId);
    }

    @Transactional
    public HomeworkAssignmentResponse createAssignment(
            UUID userId,
            UUID childId,
            HomeworkAssignmentCreateRequest request
    ) {
        Child child = loadChildWithAnyPermission(userId, childId, ChildPermissionType.ASSIGN_MISSION, ChildPermissionType.MANAGE);

        int week = resolveWeek(request.week(), request.strategyFocus());
        StrategyFocus strategyFocus = request.strategyFocus() == null
                ? StrategyFocus.ofWeek(week)
                : request.strategyFocus();

        HomeworkAssignment homework = HomeworkAssignment.builder()
                .child(child)
                .week(week)
                .strategyFocus(strategyFocus)
                .instruction(trimToNull(request.instruction()))
                .strategyTip(trimToNull(request.strategyTip()))
                .strategyTipSource(request.strategyTipSource() == null ? StrategyTipSource.MANUAL : request.strategyTipSource())
                .dueDate(request.dueDate() == null ? LocalDate.now().plusDays(7) : request.dueDate())
                .status(HomeworkStatus.PENDING)
                .build();

        return toResponse(homeworkAssignmentRepository.save(homework));
    }

    @Transactional
    public HomeworkAssignmentResponse generateOfflineMission(
            UUID userId,
            UUID childId,
            HomeworkGenerateMissionRequest request
    ) {
        Child child = loadChildWithAnyPermission(userId, childId, ChildPermissionType.ASSIGN_MISSION, ChildPermissionType.MANAGE);

        StrategyFocus strategyFocus = resolveMissionStrategy(childId, request);
        int week = strategyFocus.getWeek();
        String therapistInstruction = firstNonBlank(request.therapistInstruction(), request.request());
        String childSummary = firstNonBlank(request.childSummary(), buildAutoChildSummary(childId));
        String additionalContext = firstNonBlank(request.additionalContext(),
                buildAutoAdditionalContext(childId, strategyFocus, therapistInstruction));
        String userRequest = buildOfflineMissionUserRequest(strategyFocus, therapistInstruction);
        String retrievalQuery = firstNonBlank(request.retrievalQuery(),
                buildOfflineMissionRetrievalQuery(strategyFocus, therapistInstruction, childSummary));

        RagGenerateResponse generated = ragGenerationService.generateOfflineMission(new RagGenerateRequest(
                childId,
                userRequest,
                retrievalQuery,
                childSummary,
                additionalContext,
                request.templateKey(),
                request.topK(),
                request.similarityThreshold(),
                request.useProModel(),
                false,
                request.think()
        ));
        OfflineMissionDraft draft = parseOfflineMissionDraft(generated.generatedText())
                .orElseGet(() -> OfflineMissionDraft.fallback(
                        "오프라인 미션",
                        userRequest,
                        generated.generatedText()));

        HomeworkAssignment homework = HomeworkAssignment.builder()
                .child(child)
                .week(week)
                .strategyFocus(strategyFocus)
                .instruction(trimToNull(draft.instruction()))
                .strategyTip(trimToNull(draft.toStrategyTip()))
                .strategyTipSource(StrategyTipSource.LLM_FLASH)
                .dueDate(request.dueDate() == null ? LocalDate.now().plusDays(7) : request.dueDate())
                .status(HomeworkStatus.PENDING)
                .build();

        return toResponse(homeworkAssignmentRepository.save(homework));
    }

    @Transactional
    public HomeworkAssignmentResponse updateAssignment(
            UUID userId,
            UUID childId,
            UUID homeworkId,
            HomeworkAssignmentUpdateRequest request
    ) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.ASSIGN_MISSION, ChildPermissionType.MANAGE);
        HomeworkAssignment homework = loadHomework(childId, homeworkId);

        int week = request.week() == null && request.strategyFocus() == null
                ? homework.getWeek()
                : resolveWeek(request.week(), request.strategyFocus());
        StrategyFocus strategyFocus = request.strategyFocus() == null
                ? StrategyFocus.ofWeek(week)
                : request.strategyFocus();

        homework.updateAssignment(
                week,
                strategyFocus,
                request.instruction() == null ? homework.getInstruction() : trimToNull(request.instruction()),
                request.strategyTip() == null ? homework.getStrategyTip() : trimToNull(request.strategyTip()),
                request.strategyTipSource() == null ? homework.getStrategyTipSource() : request.strategyTipSource(),
                request.dueDate() == null ? homework.getDueDate() : request.dueDate()
        );

        return toResponse(homework);
    }

    @Transactional
    public HomeworkAssignmentResponse cancelAssignment(UUID userId, UUID childId, UUID homeworkId) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.ASSIGN_MISSION, ChildPermissionType.MANAGE);
        HomeworkAssignment homework = loadHomework(childId, homeworkId);
        homework.cancel();
        return toResponse(homework);
    }

    @Transactional
    public HomeworkAssignmentResponse submitReport(
            UUID userId,
            UUID childId,
            UUID homeworkId,
            HomeworkReportSubmitRequest request
    ) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.WRITE_NOTE);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found."));
        HomeworkAssignment homework = loadHomework(childId, homeworkId);

        if (homeworkReportRepository.existsByHomework_HomeworkId(homeworkId)) {
            throw new InvalidRequestException("이미 제출된 숙제 리포트가 있습니다.");
        }
        if (homework.getStatus() == HomeworkStatus.PENDING) {
            homework.submit();
        } else if (homework.getStatus() != HomeworkStatus.SUBMITTED) {
            throw new InvalidRequestException("제출할 수 없는 숙제 상태입니다. status=" + homework.getStatus());
        }

        HomeworkReport report = HomeworkReport.builder()
                .homework(homework)
                .reportedBy(user)
                .completed(request.completed())
                .initiatedBy(request.initiatedBy())
                .strategyApplied(request.strategyApplied() == null ? homework.getStrategyFocus() : request.strategyApplied())
                .parentObservation(trimToNull(request.parentObservation()))
                .peerResponseObserved(trimToNull(request.peerResponseObserved()))
                .spontaneousFlag(Boolean.TRUE.equals(request.spontaneousFlag()))
                .build();
        homeworkReportRepository.save(report);

        // 숙제 제출 완료 → 치료사에게 알림
        Child child = homework.getChild();
        eventPublisher.publishEvent(new HomeworkSubmittedEvent(
                homeworkId, childId, child.getName(), homework.getStrategyFocus()));

        return toResponse(homework);
    }

    @Transactional
    public HomeworkAssignmentResponse updateReport(
            UUID userId,
            UUID childId,
            UUID homeworkId,
            HomeworkReportSubmitRequest request
    ) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.WRITE_NOTE);
        HomeworkAssignment homework = loadHomework(childId, homeworkId);
        if (homework.getStatus() != HomeworkStatus.SUBMITTED) {
            throw new InvalidRequestException("검토 전 제출 기록만 수정할 수 있습니다. status=" + homework.getStatus());
        }
        HomeworkReport report = homeworkReportRepository.findByHomework_HomeworkId(homeworkId)
                .orElseThrow(() -> new InvalidRequestException("수정할 제출 기록이 없습니다."));

        report.updateSubmission(
                request.completed(),
                request.initiatedBy(),
                request.strategyApplied() == null ? homework.getStrategyFocus() : request.strategyApplied(),
                trimToNull(request.parentObservation()),
                trimToNull(request.peerResponseObserved()),
                request.spontaneousFlag()
        );
        return toResponse(homework);
    }

    @Transactional
    public HomeworkAssignmentResponse review(
            UUID userId,
            UUID childId,
            UUID homeworkId,
            HomeworkReviewRequest request
    ) {
        loadChildWithAnyPermission(userId, childId, ChildPermissionType.ASSIGN_MISSION, ChildPermissionType.MANAGE);
        User reviewer = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found."));
        HomeworkAssignment homework = loadHomework(childId, homeworkId);
        HomeworkReport report = homeworkReportRepository.findByHomework_HomeworkId(homeworkId)
                .orElseThrow(() -> new InvalidRequestException("Submitted homework report is required before review."));
        homework.review();
        report.review(reviewer, request == null ? null : trimToNull(request.reviewComment()));

        StrategyFocus strategyApplied = report.getStrategyApplied();
        if (strategyApplied != null) {
            try {
                PeersTheme theme = PeersTheme.ofWeek(strategyApplied.getWeek());
                dialogueStatisticsService.recordOfflineOutcome(childId, theme, Boolean.TRUE.equals(report.getSpontaneousFlag()));
            } catch (IllegalArgumentException e) {
                log.warn("오프라인 통계 업데이트 스킵: strategyFocus={}, week={}",
                        strategyApplied, strategyApplied.getWeek(), e);
            }
        }

        // 검토 완료 → 제출한 보호자에게 알림
        Child child = homework.getChild();
        eventPublisher.publishEvent(new HomeworkReviewedEvent(
                homeworkId, childId, child.getName(),
                homework.getStrategyFocus(), report.getReportedBy().getUserId()));

        return toResponse(homework);
    }

    private HomeworkAssignmentResponse toResponse(HomeworkAssignment homework) {
        HomeworkReportResponse report = homeworkReportRepository.findByHomework_HomeworkId(homework.getHomeworkId())
                .map(HomeworkReportResponse::from)
                .orElse(null);
        return HomeworkAssignmentResponse.from(homework, report);
    }

    private HomeworkAssignment loadHomework(UUID childId, UUID homeworkId) {
        return homeworkAssignmentRepository.findByHomeworkIdAndChild_ChildId(homeworkId, childId)
                .orElseThrow(() -> new EntityNotFoundException("숙제를 찾을 수 없습니다."));
    }

    private Child loadChildWithAnyPermission(UUID userId, UUID childId, ChildPermissionType... permissions) {
        Child child = childRepository.findByIdWithAuthorizedUsers(childId)
                .orElseThrow(() -> new EntityNotFoundException("아동 정보를 찾을 수 없습니다."));

        for (ChildPermissionType permission : permissions) {
            if (child.hasPermission(userId, permission)) {
                return child;
            }
        }
        throw new AuthenticationFailedException("해당 아동의 숙제에 접근할 권한이 없습니다.");
    }

    private StrategyFocus resolveMissionStrategy(UUID childId, HomeworkGenerateMissionRequest request) {
        if (request.strategyFocus() != null) {
            if (request.week() != null && request.strategyFocus().getWeek() != request.week()) {
                throw new InvalidRequestException("week와 strategyFocus가 서로 맞지 않습니다.");
            }
            return request.strategyFocus();
        }
        if (request.week() != null) {
            return StrategyFocus.ofWeek(request.week());
        }

        return findWeakestDialogueStrategy(childId)
                .or(() -> findCurrentPendingStrategy(childId))
                .orElseGet(() -> StrategyFocus.ofWeek(resolveNextHomeworkWeek(childId)));
    }

    private Optional<StrategyFocus> findWeakestDialogueStrategy(UUID childId) {
        return dialogueStatSummaryRepository.findByChild_ChildId(childId).stream()
                .min(Comparator.comparingDouble(this::weaknessScore))
                .map(DialogueStatSummary::getTheme)
                .map(PeersTheme::getWeekNumber)
                .map(StrategyFocus::ofWeek);
    }

    private double weaknessScore(DialogueStatSummary summary) {
        double ema = summary.getEmaValue() == null ? 0.0 : summary.getEmaValue();
        double confidencePenalty = "LOW".equals(summary.getConfidenceLevel()) ? 0.15 : 0.0;
        double trendPenalty = "DECLINING".equals(summary.getTrendDirection()) ? 0.10 : 0.0;
        return ema - confidencePenalty - trendPenalty;
    }

    private Optional<StrategyFocus> findCurrentPendingStrategy(UUID childId) {
        return homeworkAssignmentRepository
                .findByChild_ChildIdAndStatusOrderByDueDateAscCreatedAtDesc(childId, HomeworkStatus.PENDING)
                .stream()
                .findFirst()
                .map(HomeworkAssignment::getStrategyFocus);
    }

    private int resolveNextHomeworkWeek(UUID childId) {
        return homeworkAssignmentRepository.findByChild_ChildId(childId).stream()
                .map(HomeworkAssignment::getWeek)
                .filter(week -> week != null && week >= 1 && week <= 16)
                .max(Integer::compareTo)
                .map(week -> Math.min(16, week + 1))
                .orElse(1);
    }

    private static final int CHILD_SUMMARY_MAX_CHARS = 800;

    private String buildAutoChildSummary(UUID childId) {
        StringBuilder summary = new StringBuilder(CHILD_SUMMARY_MAX_CHARS + 100);
        List<DialogueStatSummary> dialogueStats = dialogueStatSummaryRepository.findByChild_ChildId(childId);
        if (!dialogueStats.isEmpty()) {
            summary.append("[대화 통계]\n");
            dialogueStats.stream()
                    .sorted(Comparator.comparingInt(s -> s.getTheme().getWeekNumber()))
                    .limit(6)
                    .forEach(s -> {
                        if (summary.length() < CHILD_SUMMARY_MAX_CHARS) {
                            summary.append("- ")
                                    .append(strategyFocusLabel(StrategyFocus.ofWeek(s.getTheme().getWeekNumber())))
                                    .append(": EMA=").append(formatNullable(s.getEmaValue()))
                                    .append(", 추세=").append(nullToDash(s.getTrendDirection()))
                                    .append(", 신뢰도=").append(nullToDash(s.getConfidenceLevel()))
                                    .append(", 세션=").append(s.getSessionCount())
                                    .append('\n');
                        }
                    });
        }

        List<ExpressionStatSummary> expressionStats = expressionStatSummaryRepository.findByChild_ChildId(childId);
        if (!expressionStats.isEmpty()) {
            summary.append("[표정 통계]\n");
            expressionStats.stream()
                    .sorted(Comparator.comparing(ExpressionStatSummary::getSuccessRate))
                    .limit(4)
                    .forEach(s -> {
                        if (summary.length() < CHILD_SUMMARY_MAX_CHARS) {
                            summary.append("- ")
                                    .append(s.getEmotionTarget())
                                    .append(": 성공률=").append(formatNullable(s.getSuccessRate()))
                                    .append(", 추세=").append(nullToDash(s.getTrendDirection()))
                                    .append(", 신뢰도=").append(nullToDash(s.getConfidenceLevel()))
                                    .append('\n');
                        }
                    });
        }

        HomeworkMissionSummaryResponse missionSummary = buildMissionSummary(childId);
        summary.append("[오프라인 미션]\n")
                .append("- 제출률=").append(String.format("%.2f", missionSummary.submissionRate()))
                .append(", 완료율=").append(String.format("%.2f", missionSummary.completionRate()))
                .append(", 자발성=").append(String.format("%.2f", missionSummary.spontaneousRate()))
                .append(", 기한초과=").append(missionSummary.overduePendingCount())
                .append('\n');

        String result = summary.toString();
        return result.length() > CHILD_SUMMARY_MAX_CHARS ? result.substring(0, CHILD_SUMMARY_MAX_CHARS) : result;
    }

    private String buildAutoAdditionalContext(UUID childId, StrategyFocus strategyFocus, String therapistInstruction) {
        List<HomeworkReport> reports = homeworkReportRepository.findByHomework_Child_ChildId(childId);
        String recentObservations = reports.stream()
                .sorted(Comparator.comparing(HomeworkReport::getReportedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(3)
                .map(report -> "- " + nullToDash(report.getParentObservation()))
                .collect(Collectors.joining("\n"));

        return """
                자동 선택된 주차: %d
                자동 선택된 전략: %s
                치료사 추가 지시: %s
                최근 보호자 관찰:
                %s
                """.formatted(
                strategyFocus.getWeek(),
                strategyFocusLabel(strategyFocus),
                nullToDash(therapistInstruction),
                recentObservations.isBlank() ? "- 없음" : recentObservations
        );
    }

    private String buildOfflineMissionUserRequest(StrategyFocus strategyFocus, String therapistInstruction) {
        return """
                PEERS %d주차 '%s'에 맞는 가정 오프라인 미션을 생성해 주세요.
                치료사 추가 지시: %s
                보호자가 바로 실행할 수 있도록 짧고 구체적인 미션으로 작성해 주세요.
                """.formatted(
                strategyFocus.getWeek(),
                strategyFocusLabel(strategyFocus),
                nullToDash(therapistInstruction)
        );
    }

    private String buildOfflineMissionRetrievalQuery(
            StrategyFocus strategyFocus,
            String therapistInstruction,
            String childSummary
    ) {
        return """
                PEERS %d주차 %s 가정 오프라인 미션
                치료사 지시: %s
                아동 통계 요약: %s
                """.formatted(
                strategyFocus.getWeek(),
                strategyFocusLabel(strategyFocus),
                nullToDash(therapistInstruction),
                childSummary.length() > 600 ? childSummary.substring(0, 600) : childSummary
        );
    }

    private Optional<OfflineMissionDraft> parseOfflineMissionDraft(String generatedText) {
        String json = stripMarkdownFence(generatedText);
        try {
            JsonNode root = objectMapper.readTree(json);
            return Optional.of(new OfflineMissionDraft(
                    text(root, "title"),
                    text(root, "goal"),
                    text(root, "instruction"),
                    text(root, "strategyTip"),
                    text(root, "childPrompt"),
                    list(root, "steps"),
                    list(root, "observationChecklist"),
                    text(root, "difficultyDown"),
                    text(root, "difficultyUp"),
                    generatedText
            ));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    private String stripMarkdownFence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstNewLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewLine >= 0 && lastFence > firstNewLine) {
                return text.substring(firstNewLine + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? null : trimToNull(node.asText());
    }

    private List<String> list(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        node.forEach(item -> {
            String value = trimToNull(item.asText());
            if (value != null) {
                values.add(value);
            }
        });
        return values;
    }

    private String firstNonBlank(String first, String second) {
        String normalized = trimToNull(first);
        return normalized != null ? normalized : trimToNull(second);
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String formatNullable(Double value) {
        return value == null ? "-" : String.format("%.2f", value);
    }

    private HomeworkMissionSummaryResponse buildMissionSummary(UUID childId) {
        List<HomeworkAssignment> assignments = homeworkAssignmentRepository.findByChild_ChildId(childId);
        List<HomeworkReport> reports = homeworkReportRepository.findByHomework_Child_ChildId(childId);
        Map<UUID, HomeworkReport> reportByHomeworkId = reports.stream()
                .collect(Collectors.toMap(
                        report -> report.getHomework().getHomeworkId(),
                        report -> report,
                        (first, ignored) -> first));

        SummaryCounter total = new SummaryCounter();
        Map<Integer, SummaryCounter> byWeek = new java.util.TreeMap<>();
        Map<Integer, StrategyFocus> strategyByWeek = new java.util.HashMap<>();
        LocalDate today = LocalDate.now();

        for (HomeworkAssignment assignment : assignments) {
            HomeworkReport report = reportByHomeworkId.get(assignment.getHomeworkId());
            total.accept(assignment, report, today);
            byWeek.computeIfAbsent(assignment.getWeek(), ignored -> new SummaryCounter())
                    .accept(assignment, report, today);
            strategyByWeek.putIfAbsent(assignment.getWeek(), assignment.getStrategyFocus());
        }

        List<HomeworkMissionSummaryResponse.WeekSummary> weeks = byWeek.entrySet().stream()
                .map(entry -> {
                    int week = entry.getKey();
                    SummaryCounter counter = entry.getValue();
                    StrategyFocus strategy = strategyByWeek.get(week);
                    return new HomeworkMissionSummaryResponse.WeekSummary(
                            week,
                            strategy == null ? null : strategy.name(),
                            strategyFocusLabel(strategy),
                            counter.assignedCount,
                            counter.submittedCount,
                            counter.doneCount,
                            counter.partialCount,
                            counter.notDoneCount,
                            ratio(counter.submittedCount, counter.assignedCount),
                            ratio(counter.doneCount + counter.partialCount, counter.assignedCount),
                            ratio(counter.doneCount, counter.submittedCount),
                            ratio(counter.spontaneousCount, counter.submittedCount)
                    );
                })
                .toList();

        return new HomeworkMissionSummaryResponse(
                childId,
                total.assignedCount,
                total.statusCounts.getOrDefault(HomeworkStatus.PENDING, 0),
                total.statusCounts.getOrDefault(HomeworkStatus.SUBMITTED, 0),
                total.statusCounts.getOrDefault(HomeworkStatus.REVIEWED, 0),
                total.statusCounts.getOrDefault(HomeworkStatus.CANCELED, 0),
                total.overduePendingCount,
                total.dueSoonPendingCount,
                total.doneCount,
                total.partialCount,
                total.notDoneCount,
                total.spontaneousCount,
                ratio(total.submittedCount, total.assignedCount),
                ratio(total.doneCount + total.partialCount, total.assignedCount),
                ratio(total.doneCount, total.submittedCount),
                ratio(total.spontaneousCount, total.submittedCount),
                weeks
        );
    }

    private int resolveWeek(Integer week, StrategyFocus strategyFocus) {
        if (week == null && strategyFocus == null) {
            throw new InvalidRequestException("week 또는 strategyFocus 중 하나는 필요합니다.");
        }
        if (week == null) {
            return strategyFocus.getWeek();
        }
        if (week < 1 || week > 16) {
            throw new InvalidRequestException("week는 1~16 사이여야 합니다.");
        }
        if (strategyFocus != null && strategyFocus.getWeek() != week) {
            throw new InvalidRequestException("week와 strategyFocus가 서로 맞지 않습니다.");
        }
        return week;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }

    public static String strategyFocusLabel(StrategyFocus strategyFocus) {
        if (strategyFocus == null) return null;
        return switch (strategyFocus) {
            case INFORMATION_EXCHANGE -> "정보 교환하기";
            case CONVERSATION_MAINTENANCE -> "대화 유지하기";
            case FINDING_COMMON_GROUND -> "공통점 찾기";
            case CONVERSATION_INITIATION -> "대화 시작하기";
            case CONVERSATION_EXIT -> "대화 마무리하기";
            case DIGITAL_COMMUNICATION -> "전자 의사소통";
            case FRIEND_SELECTION -> "친구 선택하기";
            case HUMOR_USE -> "유머 사용하기";
            case GOOD_SPORTSMANSHIP -> "좋은 스포츠맨십";
            case PLAYING_TOGETHER -> "함께 놀기";
            case CONFLICT_RESOLUTION -> "갈등 해결하기";
            case HANDLING_TEASING -> "놀림에 대처하기";
            case HANDLING_EXCLUSION -> "소외에 대처하기";
            case HANDLING_CYBERBULLYING -> "사이버 괴롭힘 대처하기";
            case HANDLING_RUMORS -> "소문과 험담 대처하기";
            case REPUTATION_MANAGEMENT -> "평판 관리하기";
        };
    }

    private record OfflineMissionDraft(
            String title,
            String goal,
            String instruction,
            String strategyTip,
            String childPrompt,
            List<String> steps,
            List<String> observationChecklist,
            String difficultyDown,
            String difficultyUp,
            String rawText
    ) {
        static OfflineMissionDraft fallback(String title, String instruction, String rawText) {
            return new OfflineMissionDraft(title, null, instruction, rawText, null,
                    List.of(), List.of(), null, null, rawText);
        }

        String toStrategyTip() {
            StringBuilder builder = new StringBuilder();
            appendSection(builder, "미션 이름", title);
            appendSection(builder, "목표", goal);
            appendSection(builder, "보호자 안내", strategyTip);
            appendSection(builder, "아이에게 말해줄 문장", childPrompt);
            appendList(builder, "수행 방법", steps);
            appendList(builder, "관찰 체크포인트", observationChecklist);
            appendSection(builder, "쉽게 조절하기", difficultyDown);
            appendSection(builder, "어렵게 조절하기", difficultyUp);
            if (builder.isEmpty()) {
                return rawText;
            }
            return builder.toString().trim();
        }

        private static void appendSection(StringBuilder builder, String title, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            builder.append(title).append(": ").append(value).append('\n');
        }

        private static void appendList(StringBuilder builder, String title, List<String> values) {
            if (values == null || values.isEmpty()) {
                return;
            }
            builder.append(title).append(":\n");
            for (String value : values) {
                builder.append("- ").append(value).append('\n');
            }
        }
    }

    private static class SummaryCounter {
        private int assignedCount;
        private int submittedCount;
        private int overduePendingCount;
        private int dueSoonPendingCount;
        private int doneCount;
        private int partialCount;
        private int notDoneCount;
        private int spontaneousCount;
        private final Map<HomeworkStatus, Integer> statusCounts = new EnumMap<>(HomeworkStatus.class);

        private void accept(HomeworkAssignment assignment, HomeworkReport report, LocalDate today) {
            assignedCount++;
            statusCounts.merge(assignment.getStatus(), 1, Integer::sum);
            if (assignment.getStatus() == HomeworkStatus.PENDING && assignment.getDueDate() != null) {
                if (assignment.getDueDate().isBefore(today)) {
                    overduePendingCount++;
                } else if (!assignment.getDueDate().isAfter(today.plusDays(2))) {
                    dueSoonPendingCount++;
                }
            }
            if (report == null) {
                return;
            }
            submittedCount++;
            if (Boolean.TRUE.equals(report.getSpontaneousFlag())) {
                spontaneousCount++;
            }
            switch (report.getCompleted()) {
                case DONE -> doneCount++;
                case PARTIAL -> partialCount++;
                case NOT_DONE -> notDoneCount++;
            }
        }
    }
}
