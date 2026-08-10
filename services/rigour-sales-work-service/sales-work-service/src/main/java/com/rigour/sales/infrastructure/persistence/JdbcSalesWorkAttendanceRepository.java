package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Sales Work 外勤事实 JDBC 仓储；不创建 CRM 或 HR 的写模型。 */
@Repository
public class JdbcSalesWorkAttendanceRepository implements SalesWorkAttendanceRepository {

    private final JdbcTemplate jdbc;

    public JdbcSalesWorkAttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public java.util.Optional<WorkDaySnapshot> findWorkDay(UUID tenantId, UUID salesProfileId, UUID workDayId) {
        List<WorkDaySnapshot> rows = jdbc.query(workDaySql("d.id=?"), (rs, row) -> snapshot(rs),
                bin(tenantId), bin(salesProfileId), bin(workDayId));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Optional<WorkDaySnapshot> findWorkDay(UUID tenantId, UUID salesProfileId,
                                                            LocalDate businessDate) {
        List<WorkDaySnapshot> rows = jdbc.query(workDaySql("d.business_date=?"), (rs, row) -> snapshot(rs),
                bin(tenantId), bin(salesProfileId), businessDate);
        return rows.stream().findFirst();
    }

    @Override
    public List<WorkDaySnapshot> findWorkDays(UUID tenantId, UUID salesProfileId,
                                               LocalDate from, LocalDate to) {
        return jdbc.query(workDaySelect() + """
                 WHERE d.tenant_id=? AND d.sales_profile_id=?
                   AND d.business_date BETWEEN ? AND ?
                 ORDER BY d.business_date
                """, (rs, row) -> snapshot(rs), bin(tenantId), bin(salesProfileId), from, to);
    }

    @Override
    public void insertWorkDay(UUID id, UUID tenantId, UUID employeeId, UUID salesProfileId,
                              LocalDate businessDate, String timezoneId, UUID fieldPolicyVersionId,
                              Instant checkedInAt) {
        jdbc.update("""
                INSERT INTO sales_work_day
                    (id, tenant_id, employee_id, sales_profile_id, business_date, timezone_id,
                     field_policy_version_id, status, checked_in_at, evidence_quality,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 'PENDING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(employeeId), bin(salesProfileId), businessDate,
                timezoneId, bin(fieldPolicyVersionId), timestamp(checkedInAt));
    }

    @Override
    public void insertPunchEvent(UUID id, UUID tenantId, UUID workDayId, String eventType,
                                 String deviceEventId, Instant clientOccurredAt, Instant serverReceivedAt,
                                 BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
                                 String deviceIdHash, String networkType, UUID policyVersionId) {
        jdbc.update("""
                INSERT INTO sales_punch_event
                    (id, tenant_id, work_day_id, event_type, device_event_id, client_occurred_at,
                     server_received_at, longitude, latitude, accuracy_meters, device_id_hash,
                     network_type, evidence_status, policy_version_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(workDayId), eventType, deviceEventId,
                timestamp(clientOccurredAt), timestamp(serverReceivedAt), longitude, latitude,
                accuracyMeters, deviceIdHash, networkType, bin(policyVersionId));
    }

    @Override
    public void insertLocationSession(UUID id, UUID tenantId, UUID workDayId, Instant startedAt,
                                      int expectedIntervalMinutes) {
        jdbc.update("""
                INSERT INTO sales_location_session
                    (id, tenant_id, work_day_id, status, started_at, expected_interval_minutes,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(workDayId), timestamp(startedAt), expectedIntervalMinutes);
    }

    @Override
    public List<String> findExistingLocationEventIds(UUID tenantId, List<String> deviceEventIds) {
        if (deviceEventIds == null || deviceEventIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(deviceEventIds.size(), "?"));
        List<Object> args = new ArrayList<>(deviceEventIds.size() + 1);
        args.add(bin(tenantId));
        args.addAll(deviceEventIds);
        return jdbc.queryForList("""
                SELECT device_event_id FROM sales_location_point
                 WHERE tenant_id=? AND device_event_id IN (""" + placeholders + ")",
                String.class, args.toArray());
    }

    @Override
    public void insertLocationPoint(UUID id, UUID tenantId, UUID locationSessionId, UUID workDayId,
                                    String deviceEventId, BigDecimal longitude, BigDecimal latitude,
                                    BigDecimal accuracyMeters, Instant clientOccurredAt,
                                    Instant serverReceivedAt, String source, String qualityStatus) {
        jdbc.update("""
                INSERT INTO sales_location_point
                    (id, tenant_id, location_session_id, work_day_id, device_event_id,
                     longitude, latitude, accuracy_meters, client_occurred_at, server_received_at,
                     source, quality_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(locationSessionId), bin(workDayId), deviceEventId,
                longitude, latitude, accuracyMeters, timestamp(clientOccurredAt), timestamp(serverReceivedAt),
                source, qualityStatus);
    }

    @Override
    public int incrementLocationPointCount(UUID tenantId, UUID workDayId, UUID locationSessionId,
                                           int amount) {
        return jdbc.update("""
                UPDATE sales_location_session
                   SET point_count=point_count+?, version=version+1, updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND work_day_id=? AND id=? AND status='ACTIVE'
                """, amount, bin(tenantId), bin(workDayId), bin(locationSessionId));
    }

    @Override
    public void insertInterruption(UUID id, UUID tenantId, UUID workDayId, String interruptionType,
                                   Instant startedAt, Instant endedAt, Integer durationSeconds,
                                   String clientDetail) {
        jdbc.update("""
                INSERT INTO sales_work_interruption
                    (id, tenant_id, work_day_id, interruption_type, started_at, ended_at,
                     duration_seconds, client_detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(workDayId), interruptionType, timestamp(startedAt),
                timestamp(endedAt), durationSeconds, clientDetail);
    }

    @Override
    public int incrementInterruptionCount(UUID tenantId, UUID workDayId, UUID locationSessionId) {
        return jdbc.update("""
                UPDATE sales_location_session
                   SET interruption_count=interruption_count+1, version=version+1,
                       updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND work_day_id=? AND id=? AND status='ACTIVE'
                """, bin(tenantId), bin(workDayId), bin(locationSessionId));
    }

    @Override
    public int finishWorkDay(UUID tenantId, UUID workDayId, Instant checkedOutAt,
                             int verifiedWorkMinutes) {
        return jdbc.update("""
                UPDATE sales_work_day
                   SET status='FINISHED', checked_out_at=?, verified_work_minutes=?,
                       version=version+1, updated_at=UTC_TIMESTAMP(6)
                WHERE tenant_id=? AND id=? AND status='ACTIVE'
                """, timestamp(checkedOutAt), verifiedWorkMinutes, bin(tenantId), bin(workDayId));
    }

    @Override
    public int reopenWorkDay(UUID tenantId, UUID workDayId) {
        return jdbc.update("""
                UPDATE sales_work_day
                   SET status='ACTIVE', checked_out_at=NULL, version=version+1,
                       updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND id=? AND status='FINISHED'
                """, bin(tenantId), bin(workDayId));
    }

    @Override
    public Optional<Instant> findLatestPunchReceivedAt(UUID tenantId, UUID workDayId, String eventType) {
        List<Instant> rows = jdbc.query("""
                SELECT server_received_at FROM sales_punch_event
                 WHERE tenant_id=? AND work_day_id=? AND event_type=?
                 ORDER BY server_received_at DESC LIMIT 1
                """, (rs, row) -> rs.getTimestamp("server_received_at").toInstant(),
                bin(tenantId), bin(workDayId), eventType);
        return rows.stream().findFirst();
    }

    @Override
    public int nextSummaryVersion(UUID tenantId, UUID workDayId) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(summary_version), 0) FROM sales_work_day_summary
                 WHERE tenant_id=? AND work_day_id=?
                """, Integer.class, bin(tenantId), bin(workDayId));
        return (current == null ? 0 : current) + 1;
    }

    @Override
    public int closeLocationSession(UUID tenantId, UUID workDayId, UUID locationSessionId,
                                    Instant endedAt) {
        return jdbc.update("""
                UPDATE sales_location_session
                   SET status='CLOSED', ended_at=?, version=version+1, updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND work_day_id=? AND id=? AND status='ACTIVE'
                """, timestamp(endedAt), bin(tenantId), bin(workDayId), bin(locationSessionId));
    }

    @Override
    public void insertWorkDaySummary(UUID id, UUID tenantId, UUID workDayId, Instant checkInAt,
                                     Instant checkOutAt, int verifiedWorkMinutes, int locationPointCount,
                                     int interruptionCount, String evidenceQuality, Instant finalizedAt,
                                     int summaryVersion) {
        jdbc.update("""
                INSERT INTO sales_work_day_summary
                    (id, tenant_id, work_day_id, summary_version, status, check_in_at, check_out_at,
                     verified_work_minutes, location_point_count, interruption_count,
                     submitted_visit_count, effective_visit_count, pending_review_visit_count,
                     evidence_quality, exception_codes_json, finalized_at, created_at)
                VALUES (?, ?, ?, ?, 'PENDING_REVIEW', ?, ?, ?, ?, ?, 0, 0, 0, ?, JSON_ARRAY(), ?, UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(workDayId), summaryVersion,
                timestamp(checkInAt), timestamp(checkOutAt),
                verifiedWorkMinutes, locationPointCount, interruptionCount, evidenceQuality,
                timestamp(finalizedAt));
    }

    @Override
    public List<LocationPointRow> findLocationPoints(UUID tenantId, UUID workDayId) {
        return jdbc.query("""
                SELECT longitude, latitude, accuracy_meters, client_occurred_at, server_received_at,
                       source, quality_status
                  FROM sales_location_point
                 WHERE tenant_id=? AND work_day_id=?
                 ORDER BY server_received_at ASC, created_at ASC
                """, (rs, row) -> new LocationPointRow(
                        rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("accuracy_meters"), instant(rs.getTimestamp("client_occurred_at")),
                        instant(rs.getTimestamp("server_received_at")), rs.getString("source"),
                        rs.getString("quality_status")),
                bin(tenantId), bin(workDayId));
    }

    @Override
    public List<PunchEventRow> findPunchEvents(UUID tenantId, UUID workDayId) {
        return jdbc.query("""
                SELECT event_type, client_occurred_at, server_received_at, longitude, latitude,
                       accuracy_meters, evidence_status
                  FROM sales_punch_event
                 WHERE tenant_id=? AND work_day_id=?
                 ORDER BY server_received_at ASC, created_at ASC
                """, (rs, row) -> new PunchEventRow(
                        rs.getString("event_type"), instant(rs.getTimestamp("client_occurred_at")),
                        instant(rs.getTimestamp("server_received_at")), rs.getBigDecimal("longitude"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("accuracy_meters"),
                        rs.getString("evidence_status")),
                bin(tenantId), bin(workDayId));
    }

    @Override
    public int incrementWorkDayVersion(UUID tenantId, UUID workDayId) {
        return jdbc.update("""
                UPDATE sales_work_day
                   SET version=version+1, updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND id=?
                """, bin(tenantId), bin(workDayId));
    }

    private static String workDaySql(String predicate) {
        return workDaySelect() + """
                 WHERE d.tenant_id=? AND d.sales_profile_id=? AND
                """ + " " + predicate + "\n ORDER BY d.business_date DESC LIMIT 1";
    }

    /** 多次重新签到会产生多个定位会话；月历和当日卡片都展示累计采样事实。 */
    private static String workDaySelect() {
        return """
                SELECT d.id, d.employee_id, d.sales_profile_id, d.business_date, d.timezone_id,
                       d.field_policy_version_id, d.status, d.checked_in_at, d.checked_out_at,
                       d.verified_work_minutes, d.evidence_quality,
                       (SELECT session.id FROM sales_location_session session
                         WHERE session.tenant_id=d.tenant_id AND session.work_day_id=d.id
                         ORDER BY session.created_at DESC LIMIT 1) AS location_session_id,
                       COALESCE((SELECT SUM(session.point_count) FROM sales_location_session session
                         WHERE session.tenant_id=d.tenant_id AND session.work_day_id=d.id), 0)
                         AS location_point_count,
                       COALESCE((SELECT SUM(session.interruption_count) FROM sales_location_session session
                         WHERE session.tenant_id=d.tenant_id AND session.work_day_id=d.id), 0)
                         AS interruption_count
                  FROM sales_work_day d
                """;
    }

    private static WorkDaySnapshot snapshot(ResultSet rs) throws SQLException {
        return new WorkDaySnapshot(uuid(rs, "id"), uuid(rs, "employee_id"), uuid(rs, "sales_profile_id"),
                rs.getObject("business_date", LocalDate.class), rs.getString("timezone_id"),
                uuid(rs, "field_policy_version_id"), rs.getString("status"),
                instant(rs.getTimestamp("checked_in_at")), instant(rs.getTimestamp("checked_out_at")),
                uuid(rs, "location_session_id"), rs.getInt("location_point_count"),
                rs.getInt("interruption_count"), rs.getInt("verified_work_minutes"),
                rs.getString("evidence_quality"));
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
        return SalesUuidCodec.encode(value);
    }
}
