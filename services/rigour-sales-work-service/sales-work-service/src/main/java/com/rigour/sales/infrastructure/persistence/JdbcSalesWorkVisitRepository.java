package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesWorkVisitRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 拜访事实 JDBC 仓储。
 *
 * <p>CRM 投影补偿写入：crm_store_projection / crm_sales_assignment_projection 名义上是 CRM
 * 只读投影，但当前 CRM 域还没有门店收录 API，第一次拜访附近 POI 时由 Sales Work 负责落库，
 * 让该门店立即进入“我的门店”。这是明确的上游缺口补偿，待 CRM 域提供门店收录/归属写入 API
 * 后，这里的两条 upsert 必须移除并改由 CRM 消费拜访事件。</p>
 */
@Repository
public class JdbcSalesWorkVisitRepository implements SalesWorkVisitRepository {

    private final JdbcTemplate jdbc;

    public JdbcSalesWorkVisitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public java.util.Optional<VisitSnapshot> findVisit(UUID tenantId, UUID salesProfileId, UUID visitId) {
        List<VisitSnapshot> rows = jdbc.query("""
                SELECT id, work_day_id, sales_profile_id, target_type, customer_id, store_id,
                       status, checked_in_at, checked_out_at, visit_policy_version_id, created_at,
                       contact_outcome, kp_name, kp_phone, intention_level, result_note, result_submitted_at,
                       submitted_at, finalized_at, final_reason_code
                  FROM sales_visit
                 WHERE tenant_id=? AND sales_profile_id=? AND id=?
                 LIMIT 1
                """, (rs, row) -> visit(rs), bin(tenantId), bin(salesProfileId), bin(visitId));
        return rows.stream().findFirst();
    }

    @Override
    public List<VisitSnapshot> findVisits(UUID tenantId, UUID salesProfileId, int limit, int offset) {
        return jdbc.query("""
                SELECT id, work_day_id, sales_profile_id, target_type, customer_id, store_id,
                       status, checked_in_at, checked_out_at, visit_policy_version_id, created_at,
                       contact_outcome, kp_name, kp_phone, intention_level, result_note, result_submitted_at,
                       submitted_at, finalized_at, final_reason_code
                  FROM sales_visit
                 WHERE tenant_id=? AND sales_profile_id=?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, (rs, row) -> visit(rs), bin(tenantId), bin(salesProfileId), limit, offset);
    }

    @Override
    public List<VisitSnapshot> findVisits(UUID tenantId, UUID salesProfileId, LocalDate businessDate,
                                          int limit, int offset) {
        return jdbc.query("""
                SELECT visit.id, visit.work_day_id, visit.sales_profile_id, visit.target_type,
                       visit.customer_id, visit.store_id, visit.status, visit.checked_in_at,
                       visit.checked_out_at, visit.visit_policy_version_id, visit.created_at,
                       visit.contact_outcome, visit.kp_name, visit.kp_phone, visit.intention_level, visit.result_note,
                       visit.result_submitted_at, visit.submitted_at, visit.finalized_at,
                       visit.final_reason_code
                  FROM sales_visit visit
                  JOIN sales_work_day work_day
                    ON work_day.tenant_id=visit.tenant_id AND work_day.id=visit.work_day_id
                 WHERE visit.tenant_id=? AND visit.sales_profile_id=?
                   AND work_day.business_date=?
                 ORDER BY visit.created_at DESC, visit.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, row) -> visit(rs), bin(tenantId), bin(salesProfileId),
                java.sql.Date.valueOf(businessDate), limit, offset);
    }

    @Override
    public long countVisits(UUID tenantId, UUID salesProfileId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_visit
                 WHERE tenant_id=? AND sales_profile_id=?
                """, Long.class, bin(tenantId), bin(salesProfileId));
        return count == null ? 0L : count;
    }

    @Override
    public long countVisits(UUID tenantId, UUID salesProfileId, LocalDate businessDate) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sales_visit visit
                  JOIN sales_work_day work_day
                    ON work_day.tenant_id=visit.tenant_id AND work_day.id=visit.work_day_id
                 WHERE visit.tenant_id=? AND visit.sales_profile_id=?
                   AND work_day.business_date=?
                """, Long.class, bin(tenantId), bin(salesProfileId), java.sql.Date.valueOf(businessDate));
        return count == null ? 0L : count;
    }

    @Override
    public VisitActivitySummaryRow summarizeVisits(UUID tenantId, UUID salesProfileId,
                                                    LocalDate from, LocalDate to) {
        VisitActivitySummaryRow visits = jdbc.queryForObject("""
                SELECT COUNT(*) AS total_visit_count,
                       COALESCE(SUM(CASE WHEN visit.checked_out_at IS NOT NULL THEN 1 ELSE 0 END), 0)
                           AS completed_visit_count,
                       COALESCE(SUM(CASE WHEN visit.status='CHECKED_IN' THEN 1 ELSE 0 END), 0)
                           AS in_progress_visit_count,
                       COALESCE(SUM(CASE WHEN visit.finalized_at IS NOT NULL
                                             AND visit.final_reason_code='EFFECTIVE'
                                         THEN 1 ELSE 0 END), 0) AS effective_visit_count,
                       COALESCE(SUM(CASE WHEN visit.checked_out_at IS NOT NULL
                                             AND visit.finalized_at IS NULL
                                         THEN 1 ELSE 0 END), 0) AS pending_review_visit_count,
                       COALESCE(SUM(CASE WHEN NOT EXISTS (
                           SELECT 1 FROM sales_visit previous
                            WHERE previous.tenant_id=visit.tenant_id
                              AND previous.sales_profile_id=visit.sales_profile_id
                              AND previous.store_id=visit.store_id
                              AND previous.checked_in_at < visit.checked_in_at
                       ) THEN 1 ELSE 0 END), 0) AS first_visit_count,
                       COALESCE(SUM(CASE WHEN EXISTS (
                           SELECT 1 FROM sales_visit previous
                            WHERE previous.tenant_id=visit.tenant_id
                              AND previous.sales_profile_id=visit.sales_profile_id
                              AND previous.store_id=visit.store_id
                              AND previous.checked_in_at < visit.checked_in_at
                       ) THEN 1 ELSE 0 END), 0) AS revisit_count,
                       COUNT(DISTINCT visit.store_id) AS unique_store_count
                  FROM sales_visit visit
                  JOIN sales_work_day work_day
                    ON work_day.tenant_id=visit.tenant_id AND work_day.id=visit.work_day_id
                 WHERE visit.tenant_id=? AND visit.sales_profile_id=?
                   AND work_day.business_date BETWEEN ? AND ?
                """, (rs, row) -> new VisitActivitySummaryRow(
                        rs.getLong("total_visit_count"), rs.getLong("completed_visit_count"),
                        rs.getLong("in_progress_visit_count"), rs.getLong("effective_visit_count"),
                        rs.getLong("pending_review_visit_count"), rs.getLong("first_visit_count"),
                        rs.getLong("revisit_count"), rs.getLong("unique_store_count"), 0),
                bin(tenantId), bin(salesProfileId), java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        Long assignedStores = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT store_id)
                  FROM crm_sales_assignment_projection
                 WHERE tenant_id=? AND sales_profile_id=? AND store_id IS NOT NULL
                   AND status='ACTIVE' AND effective_from < DATE_ADD(?, INTERVAL 1 DAY)
                   AND (effective_to IS NULL OR effective_to >= ?)
                """, Long.class, bin(tenantId), bin(salesProfileId),
                java.sql.Date.valueOf(to), java.sql.Date.valueOf(from));
        return new VisitActivitySummaryRow(visits.totalVisitCount(), visits.completedVisitCount(),
                visits.inProgressVisitCount(), visits.effectiveVisitCount(),
                visits.pendingReviewVisitCount(), visits.firstVisitCount(), visits.revisitCount(),
                visits.uniqueStoreCount(), assignedStores == null ? 0L : assignedStores);
    }

    @Override
    public List<VisitSnapshot> findVisitsByWorkDay(UUID tenantId, UUID salesProfileId, UUID workDayId) {
        return jdbc.query("""
                SELECT id, work_day_id, sales_profile_id, target_type, customer_id, store_id,
                       status, checked_in_at, checked_out_at, visit_policy_version_id, created_at,
                       contact_outcome, kp_name, kp_phone, intention_level, result_note, result_submitted_at,
                       submitted_at, finalized_at, final_reason_code
                  FROM sales_visit
                 WHERE tenant_id=? AND sales_profile_id=? AND work_day_id=?
                 ORDER BY checked_in_at, id
                """, (rs, row) -> visit(rs), bin(tenantId), bin(salesProfileId), bin(workDayId));
    }

    @Override
    public boolean existsVisitBefore(UUID tenantId, UUID salesProfileId, UUID storeId, Instant checkedInAt) {
        if (storeId == null || checkedInAt == null) return false;
        Integer found = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM sales_visit
                     WHERE tenant_id=? AND sales_profile_id=? AND store_id=?
                       AND checked_in_at < ?
                )
                """, Integer.class, bin(tenantId), bin(salesProfileId), bin(storeId), timestamp(checkedInAt));
        return found != null && found == 1;
    }

    @Override
    public void lockSalesProfile(UUID tenantId, UUID salesProfileId) {
        jdbc.queryForObject("""
                SELECT id FROM sales_profile
                 WHERE tenant_id=? AND id=?
                 FOR UPDATE
                """, (rs, row) -> uuid(rs, "id"), bin(tenantId), bin(salesProfileId));
    }

    @Override
    public java.util.Optional<VisitSnapshot> findActiveVisit(UUID tenantId, UUID salesProfileId) {
        List<VisitSnapshot> rows = jdbc.query("""
                SELECT id, work_day_id, sales_profile_id, target_type, customer_id, store_id,
                       status, checked_in_at, checked_out_at, visit_policy_version_id, created_at,
                       contact_outcome, kp_name, kp_phone, intention_level, result_note, result_submitted_at,
                       submitted_at, finalized_at, final_reason_code
                  FROM sales_visit
                 WHERE tenant_id=? AND sales_profile_id=? AND status='CHECKED_IN'
                 ORDER BY checked_in_at DESC
                 LIMIT 1
                """, (rs, row) -> visit(rs), bin(tenantId), bin(salesProfileId));
        return rows.stream().findFirst();
    }

    @Override
    public void insertVisit(UUID id, UUID tenantId, UUID workDayId, UUID salesProfileId,
                            String targetType, UUID customerId, UUID storeId,
                            UUID visitPolicyVersionId, Instant checkedInAt) {
        jdbc.update("""
                INSERT INTO sales_visit
                    (id, tenant_id, work_day_id, sales_profile_id, target_type,
                     customer_id, store_id, visit_policy_version_id, status,
                     checked_in_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CHECKED_IN', ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(workDayId), bin(salesProfileId), targetType,
                bin(customerId), bin(storeId), bin(visitPolicyVersionId), timestamp(checkedInAt));
    }

    @Override
    public void insertTargetSnapshot(UUID id, UUID tenantId, UUID visitId, String targetType,
                                     UUID customerId, String customerName, UUID storeId,
                                     String storeName, String storeAddress,
                                     BigDecimal longitude, BigDecimal latitude,
                                     UUID assignedSalesProfileId, Instant capturedAt) {
        jdbc.update("""
                INSERT INTO sales_visit_target_snapshot
                    (id, tenant_id, visit_id, target_type, customer_id, customer_name,
                     store_id, store_name, store_address, store_longitude, store_latitude,
                     assigned_sales_profile_id, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bin(id), bin(tenantId), bin(visitId), targetType, bin(customerId),
                customerName, bin(storeId), storeName, storeAddress, longitude, latitude,
                bin(assignedSalesProfileId), timestamp(capturedAt));
    }

    @Override
    public void insertCheckpoint(UUID id, UUID tenantId, UUID visitId, String checkpointType,
                                 String deviceEventId, Instant clientOccurredAt, Instant serverReceivedAt,
                                 BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
                                 BigDecimal distanceToTargetMeters, String evidenceStatus) {
        jdbc.update("""
                INSERT INTO sales_visit_checkpoint
                    (id, tenant_id, visit_id, checkpoint_type, device_event_id,
                     client_occurred_at, server_received_at, longitude, latitude,
                     accuracy_meters, distance_to_target_meters, evidence_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(visitId), checkpointType, deviceEventId,
                timestamp(clientOccurredAt), timestamp(serverReceivedAt), longitude, latitude,
                accuracyMeters, distanceToTargetMeters, evidenceStatus);
    }

    @Override
    public int checkOutVisit(UUID tenantId, UUID salesProfileId, UUID visitId, Instant checkedOutAt) {
        return jdbc.update("""
                UPDATE sales_visit
                   SET status='CHECKED_OUT', checked_out_at=?, submitted_at=?, version=version+1,
                       updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND sales_profile_id=? AND id=? AND status='CHECKED_IN'
                """, timestamp(checkedOutAt), timestamp(checkedOutAt),
                bin(tenantId), bin(salesProfileId), bin(visitId));
    }

    @Override
    public int updateVisitResult(UUID tenantId, UUID salesProfileId, UUID visitId, String contactOutcome, String kpName,
                                 String kpPhone, String intentionLevel, String resultNote,
                                 Instant submittedAt) {
        return jdbc.update("""
                UPDATE sales_visit
                   SET contact_outcome=?, kp_name=?, kp_phone=?, intention_level=?, result_note=?,
                       result_submitted_at=?, version=version+1, updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND sales_profile_id=? AND id=?
                   AND status IN ('CHECKED_IN', 'CHECKED_OUT')
                """, contactOutcome, kpName, kpPhone, intentionLevel, resultNote, timestamp(submittedAt),
                bin(tenantId), bin(salesProfileId), bin(visitId));
    }

    @Override
    public java.util.Optional<VisitTargetSnapshot> findTargetSnapshot(UUID tenantId, UUID visitId) {
        List<VisitTargetSnapshot> rows = jdbc.query("""
                SELECT visit_id, target_type, customer_id, store_id, customer_name, store_name,
                       store_address, store_longitude, store_latitude, assigned_sales_profile_id
                  FROM sales_visit_target_snapshot
                 WHERE tenant_id=? AND visit_id=?
                 LIMIT 1
                """, (rs, row) -> new VisitTargetSnapshot(uuid(rs, "visit_id"), rs.getString("target_type"),
                uuid(rs, "customer_id"), uuid(rs, "store_id"), rs.getString("customer_name"),
                rs.getString("store_name"), rs.getString("store_address"),
                rs.getBigDecimal("store_longitude"), rs.getBigDecimal("store_latitude"),
                uuid(rs, "assigned_sales_profile_id")), bin(tenantId), bin(visitId));
        return rows.stream().findFirst();
    }

    @Override
    public List<VisitTargetSnapshot> findTargetSnapshots(UUID tenantId, List<UUID> visitIds) {
        if (visitIds == null || visitIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(visitIds.size(), "?"));
        List<Object> args = new ArrayList<>(visitIds.size() + 1);
        args.add(bin(tenantId));
        visitIds.forEach(id -> args.add(bin(id)));
        return jdbc.query("""
                SELECT visit_id, target_type, customer_id, store_id, customer_name, store_name,
                       store_address, store_longitude, store_latitude, assigned_sales_profile_id
                  FROM sales_visit_target_snapshot
                 WHERE tenant_id=? AND visit_id IN (""" + placeholders + ")",
                (rs, row) -> new VisitTargetSnapshot(uuid(rs, "visit_id"), rs.getString("target_type"),
                uuid(rs, "customer_id"), uuid(rs, "store_id"), rs.getString("customer_name"),
                rs.getString("store_name"), rs.getString("store_address"),
                rs.getBigDecimal("store_longitude"), rs.getBigDecimal("store_latitude"),
                uuid(rs, "assigned_sales_profile_id")), args.toArray());
    }

    @Override
    public List<VisitCheckpointSnapshot> findCheckpoints(UUID tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT id, checkpoint_type, device_event_id, client_occurred_at, server_received_at,
                       longitude, latitude, accuracy_meters, distance_to_target_meters, evidence_status
                  FROM sales_visit_checkpoint
                 WHERE tenant_id=? AND visit_id=?
                 ORDER BY server_received_at, id
                """, (rs, row) -> new VisitCheckpointSnapshot(uuid(rs, "id"),
                rs.getString("checkpoint_type"), rs.getString("device_event_id"),
                instant(rs.getTimestamp("client_occurred_at")), instant(rs.getTimestamp("server_received_at")),
                rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("accuracy_meters"), rs.getBigDecimal("distance_to_target_meters"),
                rs.getString("evidence_status")), bin(tenantId), bin(visitId));
    }

    @Override
    public void upsertPoiStoreProjection(UUID tenantId, UUID storeId, String storeName,
                                         String storeAddress, BigDecimal longitude, BigDecimal latitude,
                                         Instant at) {
        jdbc.update("""
                INSERT INTO crm_store_projection
                    (id, tenant_id, customer_id, store_id, customer_name, store_name,
                     store_address, longitude, latitude, store_status, source_version,
                     source_updated_at, projected_at)
                VALUES (UUID_TO_BIN(UUID()), ?, NULL, ?, NULL, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    store_name=VALUES(store_name), store_address=VALUES(store_address),
                    longitude=VALUES(longitude), latitude=VALUES(latitude),
                    store_status='ACTIVE', source_version=source_version+1,
                    source_updated_at=VALUES(source_updated_at), projected_at=VALUES(projected_at)
                """, bin(tenantId), bin(storeId), storeName, storeAddress, longitude, latitude,
                timestamp(at), timestamp(at));
    }

    @Override
    public void upsertPoiAssignmentProjection(UUID tenantId, UUID salesProfileId, UUID storeId, Instant at) {
        jdbc.update("""
                INSERT INTO crm_sales_assignment_projection
                    (id, tenant_id, sales_profile_id, customer_id, store_id, assignment_type,
                     effective_from, effective_to, status, source_version, source_updated_at, projected_at)
                VALUES (UUID_TO_BIN(UUID()), ?, ?, NULL, ?, 'VISIT', ?, NULL, 'ACTIVE', 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    status='ACTIVE', effective_to=NULL, source_version=source_version+1,
                    source_updated_at=VALUES(source_updated_at), projected_at=VALUES(projected_at)
                """, bin(tenantId), bin(salesProfileId), bin(storeId), timestamp(at),
                timestamp(at), timestamp(at));
    }

    private static VisitSnapshot visit(ResultSet rs) throws SQLException {
        return new VisitSnapshot(uuid(rs, "id"), uuid(rs, "work_day_id"), uuid(rs, "sales_profile_id"),
                rs.getString("target_type"), uuid(rs, "customer_id"), uuid(rs, "store_id"),
                rs.getString("status"), instant(rs.getTimestamp("checked_in_at")),
                instant(rs.getTimestamp("checked_out_at")), uuid(rs, "visit_policy_version_id"),
                instant(rs.getTimestamp("created_at")), rs.getString("contact_outcome"), rs.getString("kp_name"),
                rs.getString("kp_phone"), rs.getString("intention_level"),
                rs.getString("result_note"), instant(rs.getTimestamp("result_submitted_at")),
                instant(rs.getTimestamp("submitted_at")), instant(rs.getTimestamp("finalized_at")),
                rs.getString("final_reason_code"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return SalesUuidCodec.decode(rs.getBytes(column));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static byte[] bin(UUID value) {
        return value == null ? null : SalesUuidCodec.encode(value);
    }
}
