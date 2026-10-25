package myexpressionfriend_api.homework.repository;

import myexpressionfriend_api.homework.domain.HomeworkAssignment;
import myexpressionfriend_api.homework.domain.HomeworkStatus;
import myexpressionfriend_api.homework.domain.StrategyFocus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeworkAssignmentRepository extends JpaRepository<HomeworkAssignment, UUID> {

    Page<HomeworkAssignment> findByChild_ChildIdOrderByCreatedAtDesc(UUID childId, Pageable pageable);

    List<HomeworkAssignment> findByChild_ChildId(UUID childId);

    Page<HomeworkAssignment> findByChild_ChildIdAndStatusOrderByCreatedAtDesc(
            UUID childId, HomeworkStatus status, Pageable pageable);

    List<HomeworkAssignment> findByChild_ChildIdAndWeek(UUID childId, Integer week);

    List<HomeworkAssignment> findByChild_ChildIdAndStatusOrderByDueDateAscCreatedAtDesc(
            UUID childId, HomeworkStatus status);

    /**
     * 현재 진행 중인 숙제 단건 조회.
     * 이미 마감일이 지난 PENDING 데이터는 홈의 "이번 주" 미션에서 제외한다.
     * dueDate가 null인 경우 맨 뒤에 오도록 NULLS LAST를 적용한다.
     */
    @Query("""
            SELECT h FROM HomeworkAssignment h
            WHERE h.child.childId = :childId
              AND h.status = :status
              AND (h.dueDate IS NULL OR h.dueDate >= :today)
            ORDER BY CASE WHEN h.dueDate IS NULL THEN 1 ELSE 0 END ASC,
                     h.dueDate ASC,
                     h.createdAt DESC
            LIMIT 1
            """)
    Optional<HomeworkAssignment> findCurrentByChildAndStatus(
            @Param("childId") UUID childId,
            @Param("status") HomeworkStatus status,
            @Param("today") LocalDate today);

    /** 아동별 전략 누적 시도 횟수 (StrategyLog attempt_count 계산용) */
    @Query("""
            SELECT COUNT(h) FROM HomeworkAssignment h
            WHERE h.child.childId = :childId
              AND h.strategyFocus = :strategy
            """)
    long countByChildIdAndStrategy(@Param("childId") UUID childId,
                                   @Param("strategy") StrategyFocus strategy);

    Optional<HomeworkAssignment> findByHomeworkIdAndChild_ChildId(UUID homeworkId, UUID childId);

    /**
     * 만료 처리 대상: PENDING 상태이면서 dueDate가 기준일 이전인 숙제 목록.
     * 스케줄러에서 매일 자정에 호출한다.
     */
    @Query("""
            SELECT h FROM HomeworkAssignment h
            JOIN FETCH h.child c
            WHERE h.status = myexpressionfriend_api.homework.domain.HomeworkStatus.PENDING
              AND h.dueDate < :today
            """)
    List<HomeworkAssignment> findExpiredPending(@Param("today") java.time.LocalDate today);
}
