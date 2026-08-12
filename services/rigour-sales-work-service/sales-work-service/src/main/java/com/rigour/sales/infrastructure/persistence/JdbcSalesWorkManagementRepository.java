package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesWorkManagementRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 基于工作日、拜访和复核终态事实生成管理端区间统计。 */
@Repository
public class JdbcSalesWorkManagementRepository implements SalesWorkManagementRepository {

    private final JdbcTemplate jdbc;

    public JdbcSalesWorkManagementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ManagementTotalsRow totals(UUID tenantId, LocalDate from, LocalDate to) {
        byte[] tenant = bin(tenantId);
        Long activeSales = value(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_profile
                 WHERE tenant_id=? AND status='ACTIVE'
                """, Long.class, tenant));
        Long totalInterruptionCount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(session.interruption_count), 0)
                  FROM sales_location_session session
                  JOIN sales_work_day work_day
                    ON work_day.tenant_id=session.tenant_id AND work_day.id=session.work_day_id
                 WHERE session.tenant_id=? AND work_day.business_date BETWEEN ? AND ?
                """, Long.class, tenant, Date.valueOf(from), Date.valueOf(to));
        WorkTotals work = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT sales_profile_id) AS attended_sales_count,
                       COALESCE(SUM(CASE WHEN business_date=? AND status='ACTIVE' THEN 1 ELSE 0 END), 0)
                           AS working_sales_count,
                       COALESCE(SUM(CASE WHEN status IN ('FINISHED','PENDING_REVIEW') THEN 1 ELSE 0 END), 0)
                           AS finished_work_day_count
                  FROM sales_work_day work_day
                 WHERE work_day.tenant_id=? AND work_day.business_date BETWEEN ? AND ?
                """, (rs, row) -> new WorkTotals(rs.getLong("attended_sales_count"),
                        rs.getLong("working_sales_count"), rs.getLong("finished_work_day_count"),
                        value(totalInterruptionCount)), Date.valueOf(to), tenant,
                Date.valueOf(from), Date.valueOf(to));
        VisitTotals visit = jdbc.queryForObject("""
                SELECT COUNT(*) AS total_visit_count,
                       COALESCE(SUM(CASE WHEN current_visit.checked_out_at IS NOT NULL THEN 1 ELSE 0 END), 0)
                           AS completed_visit_count,
                       COALESCE(SUM(CASE WHEN current_visit.finalized_at IS NOT NULL
                                             AND current_visit.final_reason_code='EFFECTIVE'
                                         THEN 1 ELSE 0 END), 0) AS effective_visit_count,
                       COALESCE(SUM(CASE WHEN current_visit.checked_out_at IS NOT NULL
                                             AND current_visit.finalized_at IS NULL
                                         THEN 1 ELSE 0 END), 0) AS pending_review_visit_count,
                       COALESCE(SUM(CASE WHEN NOT EXISTS (
                           SELECT 1 FROM sales_visit previous
                            WHERE previous.tenant_id=current_visit.tenant_id
                              AND previous.sales_profile_id=current_visit.sales_profile_id
                              AND previous.store_id=current_visit.store_id
                              AND previous.checked_in_at < current_visit.checked_in_at
                       ) THEN 1 ELSE 0 END), 0) AS first_visit_count,
                       COALESCE(SUM(CASE WHEN EXISTS (
                           SELECT 1 FROM sales_visit previous
                            WHERE previous.tenant_id=current_visit.tenant_id
                              AND previous.sales_profile_id=current_visit.sales_profile_id
                              AND previous.store_id=current_visit.store_id
                              AND previous.checked_in_at < current_visit.checked_in_at
                       ) THEN 1 ELSE 0 END), 0) AS revisit_count,
                       COUNT(DISTINCT current_visit.store_id) AS unique_store_count
                  FROM sales_visit current_visit
                  JOIN sales_work_day work_day
                    ON work_day.tenant_id=current_visit.tenant_id
                   AND work_day.id=current_visit.work_day_id
                 WHERE current_visit.tenant_id=? AND work_day.business_date BETWEEN ? AND ?
                """, (rs, row) -> new VisitTotals(rs.getLong("total_visit_count"),
                        rs.getLong("completed_visit_count"), rs.getLong("effective_visit_count"),
                        rs.getLong("pending_review_visit_count"), rs.getLong("first_visit_count"),
                        rs.getLong("revisit_count"), rs.getLong("unique_store_count")),
                tenant, Date.valueOf(from), Date.valueOf(to));
        Long assignedStores = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT store_id)
                  FROM crm_sales_assignment_projection
                 WHERE tenant_id=? AND store_id IS NOT NULL AND status='ACTIVE'
                   AND effective_from < DATE_ADD(?, INTERVAL 1 DAY)
                   AND (effective_to IS NULL OR effective_to >= ?)
                """, Long.class, tenant, Date.valueOf(to), Date.valueOf(from));
        return new ManagementTotalsRow(activeSales, work.attendedSalesCount(), work.workingSalesCount(),
                work.finishedWorkDayCount(), work.totalInterruptionCount(), visit.totalVisitCount(),
                visit.completedVisitCount(), visit.effectiveVisitCount(), visit.pendingReviewVisitCount(),
                visit.firstVisitCount(), visit.revisitCount(), visit.uniqueStoreCount(),
                value(assignedStores));
    }

    @Override
    public List<SalesPersonActivityRow> people(UUID tenantId, LocalDate from, LocalDate to) {
        byte[] tenant = bin(tenantId);
        return jdbc.query("""
                SELECT profile.id, profile.employee_id, profile.sales_no,
                       EXISTS(SELECT 1 FROM sales_work_day active_day
                               WHERE active_day.tenant_id=profile.tenant_id
                                 AND active_day.sales_profile_id=profile.id
                                 AND active_day.business_date=? AND active_day.status='ACTIVE') AS working,
                       (SELECT COUNT(*) FROM sales_work_day work_day
                         WHERE work_day.tenant_id=profile.tenant_id
                           AND work_day.sales_profile_id=profile.id
                           AND work_day.business_date BETWEEN ? AND ?) AS work_day_count,
                       (SELECT COUNT(*) FROM sales_visit current_visit
                         JOIN sales_work_day visit_day
                           ON visit_day.tenant_id=current_visit.tenant_id
                          AND visit_day.id=current_visit.work_day_id
                        WHERE current_visit.tenant_id=profile.tenant_id
                          AND current_visit.sales_profile_id=profile.id
                          AND visit_day.business_date BETWEEN ? AND ?) AS total_visit_count,
                       (SELECT COUNT(*) FROM sales_visit current_visit
                         JOIN sales_work_day visit_day
                           ON visit_day.tenant_id=current_visit.tenant_id
                          AND visit_day.id=current_visit.work_day_id
                        WHERE current_visit.tenant_id=profile.tenant_id
                          AND current_visit.sales_profile_id=profile.id
                          AND current_visit.checked_out_at IS NOT NULL
                          AND visit_day.business_date BETWEEN ? AND ?) AS completed_visit_count,
                       (SELECT COUNT(*) FROM sales_visit current_visit
                         JOIN sales_work_day visit_day
                           ON visit_day.tenant_id=current_visit.tenant_id
                          AND visit_day.id=current_visit.work_day_id
                        WHERE current_visit.tenant_id=profile.tenant_id
                          AND current_visit.sales_profile_id=profile.id
                          AND current_visit.finalized_at IS NOT NULL
                          AND current_visit.final_reason_code='EFFECTIVE'
                          AND visit_day.business_date BETWEEN ? AND ?) AS effective_visit_count,
                       (SELECT COUNT(*) FROM sales_visit current_visit
                         JOIN sales_work_day visit_day
                           ON visit_day.tenant_id=current_visit.tenant_id
                          AND visit_day.id=current_visit.work_day_id
                        WHERE current_visit.tenant_id=profile.tenant_id
                          AND current_visit.sales_profile_id=profile.id
                          AND current_visit.checked_out_at IS NOT NULL
                          AND current_visit.finalized_at IS NULL
                          AND visit_day.business_date BETWEEN ? AND ?) AS pending_review_visit_count,
                       (SELECT COUNT(*) FROM sales_visit current_visit
                         JOIN sales_work_day visit_day
                           ON visit_day.tenant_id=current_visit.tenant_id
                          AND visit_day.id=current_visit.work_day_id
                        WHERE current_visit.tenant_id=profile.tenant_id
                          AND current_visit.sales_profile_id=profile.id
                          AND visit_day.business_date BETWEEN ? AND ?
                          AND NOT EXISTS (SELECT 1 FROM sales_visit previous
                                           WHERE previous.tenant_id=current_visit.tenant_id
                                             AND previous.sales_profile_id=current_visit.sales_profile_id
                                             AND previous.store_id=current_visit.store_id
                                             AND previous.checked_in_at < current_visit.checked_in_at))
                           AS first_visit_count,
                       (SELECT COUNT(*) FROM sales_visit current_visit
                         JOIN sales_work_day visit_day
                           ON visit_day.tenant_id=current_visit.tenant_id
                          AND visit_day.id=current_visit.work_day_id
                        WHERE current_visit.tenant_id=profile.tenant_id
                          AND current_visit.sales_profile_id=profile.id
                          AND visit_day.business_date BETWEEN ? AND ?
                          AND EXISTS (SELECT 1 FROM sales_visit previous
                                      WHERE previous.tenant_id=current_visit.tenant_id
                                        AND previous.sales_profile_id=current_visit.sales_profile_id
                                        AND previous.store_id=current_visit.store_id
                                        AND previous.checked_in_at < current_visit.checked_in_at))
                           AS revisit_count,
                       (SELECT COUNT(DISTINCT current_visit.store_id) FROM sales_visit current_visit
                         JOIN sales_work_day visit_day
                           ON visit_day.tenant_id=current_visit.tenant_id
                          AND visit_day.id=current_visit.work_day_id
                        WHERE current_visit.tenant_id=profile.tenant_id
                          AND current_visit.sales_profile_id=profile.id
                          AND visit_day.business_date BETWEEN ? AND ?) AS unique_store_count
                       ,(SELECT COUNT(DISTINCT assignment.store_id)
                           FROM crm_sales_assignment_projection assignment
                          WHERE assignment.tenant_id=profile.tenant_id
                            AND assignment.sales_profile_id=profile.id
                            AND assignment.store_id IS NOT NULL AND assignment.status='ACTIVE'
                            AND assignment.effective_from < DATE_ADD(?, INTERVAL 1 DAY)
                            AND (assignment.effective_to IS NULL OR assignment.effective_to >= ?))
                          AS assigned_store_count
                  FROM sales_profile profile
                 WHERE profile.tenant_id=? AND profile.status='ACTIVE'
                 ORDER BY completed_visit_count DESC, total_visit_count DESC, profile.sales_no
                """, (rs, row) -> new SalesPersonActivityRow(uuid(rs.getBytes("id")),
                        uuid(rs.getBytes("employee_id")), rs.getString("sales_no"), rs.getBoolean("working"),
                        rs.getLong("work_day_count"), rs.getLong("total_visit_count"),
                        rs.getLong("completed_visit_count"), rs.getLong("effective_visit_count"),
                        rs.getLong("pending_review_visit_count"), rs.getLong("first_visit_count"),
                        rs.getLong("revisit_count"), rs.getLong("unique_store_count"),
                        rs.getLong("assigned_store_count")),
                Date.valueOf(to), Date.valueOf(from), Date.valueOf(to),
                Date.valueOf(from), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to),
                Date.valueOf(from), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to),
                Date.valueOf(from), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to),
                Date.valueOf(from), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to),
                tenant);
    }

    @Override
    public List<VisitReviewRow> reviewQueue(UUID tenantId, LocalDate from, LocalDate to,
                                            int limit, int offset) {
        return jdbc.query("""
                SELECT current_visit.id AS visit_id, current_visit.sales_profile_id,
                       profile.sales_no, target.store_name, current_visit.checked_in_at,
                       current_visit.checked_out_at,
                       TIMESTAMPDIFF(MINUTE, current_visit.checked_in_at, current_visit.checked_out_at)
                           AS dwell_minutes,
                       COALESCE((SELECT FLOOR(SUM(clip.client_duration_ms) / 1000)
                  FROM sales_recording_session session
                   JOIN sales_recording_clip clip
                     ON clip.tenant_id=session.tenant_id AND clip.recording_session_id=session.id
                                  WHERE session.tenant_id=current_visit.tenant_id
                                    AND session.visit_id=current_visit.id
                                    AND clip.upload_status='RECEIVED'), 0) AS uploaded_recording_seconds,
                       COALESCE((SELECT FLOOR(session.verified_total_duration_ms / 1000)
                                   FROM sales_recording_session session
                                  WHERE session.tenant_id=current_visit.tenant_id
                                    AND session.visit_id=current_visit.id), 0)
                           AS verified_recording_seconds,
                       policy.minimum_dwell_minutes,
                       CASE WHEN policy.recording_enabled=1 THEN policy.minimum_recording_seconds ELSE 0 END
                           AS minimum_recording_seconds,
                       (SELECT COUNT(*) FROM sales_visit_evidence evidence
                         WHERE evidence.tenant_id=current_visit.tenant_id
                           AND evidence.visit_id=current_visit.id
                           AND evidence.evidence_type='PHOTO' AND evidence.evidence_role='STOREFRONT'
                           AND evidence.evidence_status='TECHNICALLY_VERIFIED')
                           AS verified_storefront_photo_count,
                       GREATEST(policy.required_photo_count, 1) AS required_storefront_photo_count,
                       current_visit.contact_outcome, current_visit.kp_name,
                       current_visit.intention_level, current_visit.result_note,
                       (SELECT assessment.review_note FROM sales_visit_review assessment
                         WHERE assessment.tenant_id=current_visit.tenant_id
                           AND assessment.visit_id=current_visit.id
                           AND assessment.review_type='AUTOMATIC_ASSESSMENT'
                         ORDER BY assessment.assigned_at DESC LIMIT 1) AS anomaly_codes,
                       CASE WHEN EXISTS (SELECT 1 FROM sales_visit previous
                                          WHERE previous.tenant_id=current_visit.tenant_id
                                            AND previous.sales_profile_id=current_visit.sales_profile_id
                                            AND previous.store_id=current_visit.store_id
                                            AND previous.checked_in_at < current_visit.checked_in_at)
                            THEN 'REVISIT' ELSE 'FIRST_VISIT' END AS visit_type
                  FROM sales_visit current_visit
                  JOIN sales_work_day work_day
                    ON work_day.tenant_id=current_visit.tenant_id AND work_day.id=current_visit.work_day_id
                  JOIN sales_profile profile
                    ON profile.tenant_id=current_visit.tenant_id AND profile.id=current_visit.sales_profile_id
                  JOIN sales_visit_target_snapshot target
                    ON target.tenant_id=current_visit.tenant_id AND target.visit_id=current_visit.id
                  JOIN sales_visit_policy_version policy
                    ON policy.tenant_id=current_visit.tenant_id
                   AND policy.id=current_visit.visit_policy_version_id
                 WHERE current_visit.tenant_id=? AND current_visit.checked_out_at IS NOT NULL
                   AND current_visit.finalized_at IS NULL
                   AND work_day.business_date BETWEEN ? AND ?
                 ORDER BY current_visit.checked_out_at, current_visit.id
                 LIMIT ? OFFSET ?
                """, (rs, row) -> new VisitReviewRow(uuid(rs.getBytes("visit_id")),
                        uuid(rs.getBytes("sales_profile_id")), rs.getString("sales_no"),
                        rs.getString("store_name"), instant(rs.getTimestamp("checked_in_at")),
                        instant(rs.getTimestamp("checked_out_at")), rs.getInt("dwell_minutes"),
                        rs.getInt("minimum_dwell_minutes"), rs.getInt("uploaded_recording_seconds"),
                        rs.getInt("verified_recording_seconds"), rs.getInt("minimum_recording_seconds"),
                        rs.getInt("verified_storefront_photo_count"),
                        rs.getInt("required_storefront_photo_count"), rs.getString("contact_outcome"),
                        rs.getString("kp_name"), rs.getString("intention_level"), rs.getString("result_note"),
                        rs.getString("visit_type"), codes(rs.getString("anomaly_codes"))),
                bin(tenantId), Date.valueOf(from), Date.valueOf(to),
                limit, offset);
    }

    @Override
    public long countReviewQueue(UUID tenantId, LocalDate from, LocalDate to) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_visit current_visit
                  JOIN sales_work_day work_day
                    ON work_day.tenant_id=current_visit.tenant_id AND work_day.id=current_visit.work_day_id
                 WHERE current_visit.tenant_id=? AND current_visit.checked_out_at IS NOT NULL
                   AND current_visit.finalized_at IS NULL
                   AND work_day.business_date BETWEEN ? AND ?
                """, Long.class, bin(tenantId), Date.valueOf(from), Date.valueOf(to));
        return value(count);
    }

    @Override
    public java.util.Optional<ReviewTargetRow> findReviewTarget(UUID tenantId, UUID visitId, boolean lock) {
        String sql = """
                SELECT visit.id, visit.final_reason_code, visit.finalized_at,
                       (SELECT review.reason_code FROM sales_visit_review review
                         WHERE review.tenant_id=visit.tenant_id AND review.visit_id=visit.id
                           AND review.review_status='DECIDED'
                         ORDER BY review.decided_at DESC LIMIT 1) AS review_reason_code
                  FROM sales_visit visit
                 WHERE visit.tenant_id=? AND visit.id=? AND visit.checked_out_at IS NOT NULL
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> new ReviewTargetRow(uuid(rs.getBytes("id")),
                rs.getString("final_reason_code"), rs.getString("review_reason_code"),
                instant(rs.getTimestamp("finalized_at"))),
                bin(tenantId), bin(visitId)).stream().findFirst();
    }

    @Override
    public int finalizeVisit(UUID tenantId, UUID visitId, String decision, String reasonCode,
                             Instant finalizedAt) {
        return jdbc.update("""
                UPDATE sales_visit
                   SET finalized_at=?, final_reason_code=?, version=version+1,
                       updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND id=? AND checked_out_at IS NOT NULL AND finalized_at IS NULL
                """, timestamp(finalizedAt), decision, bin(tenantId), bin(visitId));
    }

    @Override
    public void insertReview(UUID id, UUID tenantId, UUID visitId, UUID reviewerId,
                             String decision, String reasonCode, String reviewNote, Instant decidedAt) {
        jdbc.update("""
                INSERT INTO sales_visit_review
                    (id, tenant_id, visit_id, review_type, review_status, reviewer_id,
                     decision, reason_code, review_note, assigned_at, decided_at, version)
                VALUES (?, ?, ?, 'MANUAL', 'DECIDED', ?, ?, ?, ?, ?, ?, 0)
                """, bin(id), bin(tenantId), bin(visitId), bin(reviewerId), decision,
                reasonCode, reviewNote, timestamp(decidedAt), timestamp(decidedAt));
    }

    @Override
    public java.util.Optional<VisitAssessmentRow> findVisitAssessment(UUID tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT visit.id, visit.checked_in_at, visit.checked_out_at, visit.finalized_at,
                       visit.contact_outcome, visit.result_submitted_at,
                       policy.minimum_dwell_minutes, policy.required_photo_count,
                       (SELECT COUNT(*) FROM sales_visit_evidence evidence
                         WHERE evidence.tenant_id=visit.tenant_id AND evidence.visit_id=visit.id
                           AND evidence.evidence_type='PHOTO' AND evidence.evidence_role='STOREFRONT'
                           AND evidence.evidence_status='TECHNICALLY_VERIFIED')
                           AS verified_storefront_photo_count,
                       policy.recording_enabled, policy.minimum_recording_seconds,
                       recording.verified_total_duration_ms,
                       recording.evidence_status AS recording_evidence_status,
                       policy.ai_asr_enabled, policy.ai_relevance_enabled, policy.ai_duplicate_enabled,
                       policy.ai_auto_confirm_threshold, ai.recommendation AS ai_recommendation,
                       ai.confidence_score AS ai_confidence_score,
                       COALESCE(ai.adopted, 0) AS ai_result_adopted
                  FROM sales_visit visit
                  JOIN sales_visit_policy_version policy
                    ON policy.tenant_id=visit.tenant_id AND policy.id=visit.visit_policy_version_id
                  LEFT JOIN sales_recording_session recording
                    ON recording.tenant_id=visit.tenant_id AND recording.visit_id=visit.id
                  LEFT JOIN sales_ai_result_snapshot ai
                    ON ai.id=(SELECT candidate.id FROM sales_ai_result_snapshot candidate
                               WHERE candidate.tenant_id=visit.tenant_id
                                 AND candidate.visit_id=visit.id AND candidate.adopted=1
                               ORDER BY candidate.result_version DESC LIMIT 1)
                 WHERE visit.tenant_id=? AND visit.id=?
                 LIMIT 1
                """, (rs, row) -> new VisitAssessmentRow(
                        uuid(rs.getBytes("id")), instant(rs.getTimestamp("checked_in_at")),
                        instant(rs.getTimestamp("checked_out_at")), instant(rs.getTimestamp("finalized_at")),
                        rs.getString("contact_outcome"), instant(rs.getTimestamp("result_submitted_at")),
                        rs.getInt("minimum_dwell_minutes"), rs.getInt("required_photo_count"),
                        rs.getLong("verified_storefront_photo_count"), rs.getBoolean("recording_enabled"),
                        rs.getInt("minimum_recording_seconds"),
                        rs.getObject("verified_total_duration_ms", Long.class),
                        rs.getString("recording_evidence_status"), rs.getBoolean("ai_asr_enabled"),
                        rs.getBoolean("ai_relevance_enabled"), rs.getBoolean("ai_duplicate_enabled"),
                        rs.getBigDecimal("ai_auto_confirm_threshold"), rs.getString("ai_recommendation"),
                        rs.getBigDecimal("ai_confidence_score"), rs.getBoolean("ai_result_adopted")),
                bin(tenantId), bin(visitId)).stream().findFirst();
    }

    @Override
    public void upsertAutomaticAssessment(
            UUID id, UUID tenantId, UUID visitId, String reviewStatus, String decision,
            String reasonCode, String assessmentNote, Instant assessedAt) {
        jdbc.update("""
                INSERT INTO sales_visit_review
                    (id, tenant_id, visit_id, review_type, review_status, reviewer_id,
                     decision, reason_code, review_note, assigned_at, decided_at, version)
                VALUES (?, ?, ?, 'AUTOMATIC_ASSESSMENT', ?, NULL, ?, ?, ?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE
                    review_status=VALUES(review_status), decision=VALUES(decision),
                    reason_code=VALUES(reason_code), review_note=VALUES(review_note),
                    assigned_at=VALUES(assigned_at), decided_at=VALUES(decided_at),
                    version=version+1
                """, bin(id), bin(tenantId), bin(visitId), reviewStatus, decision,
                reasonCode, assessmentNote, timestamp(assessedAt),
                "DECIDED".equals(reviewStatus) ? timestamp(assessedAt) : null);
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static List<String> codes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(code -> !code.isEmpty()).distinct().toList();
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }

    private static UUID uuid(byte[] value) {
        return SalesUuidCodec.decode(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private record WorkTotals(long attendedSalesCount, long workingSalesCount,
                              long finishedWorkDayCount, long totalInterruptionCount) {
    }

    private record VisitTotals(long totalVisitCount, long completedVisitCount,
                               long effectiveVisitCount, long pendingReviewVisitCount,
                               long firstVisitCount, long revisitCount, long uniqueStoreCount) {
    }
}
