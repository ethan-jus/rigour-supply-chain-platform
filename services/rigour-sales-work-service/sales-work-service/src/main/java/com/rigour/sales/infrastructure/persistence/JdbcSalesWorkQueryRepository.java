package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Sales Work 查询仓储；所有读取都以租户和当前销售画像为边界。 */
@Repository
public class JdbcSalesWorkQueryRepository implements SalesWorkQueryRepository {

    private final JdbcTemplate jdbc;

    public JdbcSalesWorkQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public java.util.Optional<IdentityProjection> findIdentityProjection(UUID tenantId, UUID platformUserId) {
        List<IdentityProjection> rows = jdbc.query("""
                SELECT platform_user_id, employee_id, status
                  FROM sales_identity_projection
                 WHERE tenant_id=? AND platform_user_id=? AND status='ACTIVE'
                   AND effective_from <= UTC_TIMESTAMP(6)
                   AND (effective_to IS NULL OR effective_to > UTC_TIMESTAMP(6))
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, (rs, row) -> new IdentityProjection(
                uuid(rs, "platform_user_id"), uuid(rs, "employee_id"), rs.getString("status")),
                bin(tenantId), bin(platformUserId));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Optional<SalesProfile> findActiveSalesProfile(UUID tenantId, UUID employeeId, Instant at) {
        List<SalesProfile> rows = jdbc.query("""
                SELECT id, employee_id, sales_no, city_org_id, status
                  FROM sales_profile
                 WHERE tenant_id=? AND employee_id=? AND status='ACTIVE'
                   AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, (rs, row) -> new SalesProfile(uuid(rs, "id"), uuid(rs, "employee_id"),
                rs.getString("sales_no"), uuid(rs, "city_org_id"), rs.getString("status")),
                bin(tenantId), bin(employeeId), timestamp(at), timestamp(at));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Optional<FieldPolicy> findActiveFieldPolicy(UUID tenantId, UUID salesProfileId,
                                                                  UUID cityOrgId, Instant at) {
        List<FieldPolicy> rows = jdbc.query("""
                SELECT fpv.id, fp.policy_code, fp.policy_name, fpv.version_no, fpv.publish_status,
                       fpv.timezone_id, fpv.business_day_cutoff,
                       fpv.check_in_window_start, fpv.check_in_window_end,
                       fpv.check_out_window_start, fpv.check_out_window_end,
                       fpv.standard_work_minutes, fpv.minimum_work_minutes, fpv.require_check_out,
                       fpv.allow_adjustment, fpv.adjustment_deadline_hours, fpv.location_enabled,
                       fpv.location_interval_minutes, fpv.minimum_location_accuracy_meters,
                       fpv.offline_upload_deadline_minutes, fpv.effective_from
                  FROM sales_field_policy_version fpv
                  JOIN sales_field_policy fp ON fp.id=fpv.policy_id AND fp.tenant_id=fpv.tenant_id
                 WHERE fpv.tenant_id=? AND fpv.publish_status='PUBLISHED'
                   AND (fpv.effective_from IS NULL OR fpv.effective_from <= ?)
                   AND (fpv.effective_to IS NULL OR fpv.effective_to > ?)
                   AND (
                         NOT EXISTS (
                             SELECT 1 FROM sales_policy_scope scope_all
                              WHERE scope_all.tenant_id=fpv.tenant_id
                                AND scope_all.policy_type='FIELD'
                                AND scope_all.status='ACTIVE'
                                AND scope_all.effective_from <= ?
                                AND (scope_all.effective_to IS NULL OR scope_all.effective_to > ?)
                         )
                         OR EXISTS (
                             SELECT 1 FROM sales_policy_scope scope_match
                              WHERE scope_match.tenant_id=fpv.tenant_id
                                AND scope_match.policy_type='FIELD'
                                AND scope_match.policy_version_id=fpv.id
                                AND scope_match.status='ACTIVE'
                                AND scope_match.effective_from <= ?
                                AND (scope_match.effective_to IS NULL OR scope_match.effective_to > ?)
                                AND (
                                    (scope_match.scope_type='EMPLOYEE' AND scope_match.scope_id=?)
                                    OR (scope_match.scope_type='CITY' AND scope_match.scope_id=?)
                                    OR (scope_match.scope_type='ALL' AND scope_match.scope_id IS NULL)
                                    OR (scope_match.scope_type='TEAM' AND EXISTS (
                                        SELECT 1 FROM sales_team_member member
                                         WHERE member.tenant_id=scope_match.tenant_id
                                           AND member.team_id=scope_match.scope_id
                                           AND member.sales_profile_id=?
                                           AND member.status='ACTIVE'
                                           AND member.effective_from <= ?
                                           AND (member.effective_to IS NULL OR member.effective_to > ?)
                                    ))
                                )
                         )
                   )
                 ORDER BY fpv.version_no DESC
                 LIMIT 1
                """, (rs, row) -> policy(rs), bin(tenantId), timestamp(at), timestamp(at),
                timestamp(at), timestamp(at), timestamp(at), timestamp(at), bin(salesProfileId),
                bin(cityOrgId), bin(salesProfileId), timestamp(at), timestamp(at));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Optional<FieldPolicy> findFieldPolicy(UUID tenantId, UUID fieldPolicyVersionId) {
        List<FieldPolicy> rows = jdbc.query("""
                SELECT fpv.id, fp.policy_code, fp.policy_name, fpv.version_no, fpv.publish_status,
                       fpv.timezone_id, fpv.business_day_cutoff,
                       fpv.check_in_window_start, fpv.check_in_window_end,
                       fpv.check_out_window_start, fpv.check_out_window_end,
                       fpv.standard_work_minutes, fpv.minimum_work_minutes, fpv.require_check_out,
                       fpv.allow_adjustment, fpv.adjustment_deadline_hours, fpv.location_enabled,
                       fpv.location_interval_minutes, fpv.minimum_location_accuracy_meters,
                       fpv.offline_upload_deadline_minutes, fpv.effective_from
                  FROM sales_field_policy_version fpv
                  JOIN sales_field_policy fp ON fp.id=fpv.policy_id AND fp.tenant_id=fpv.tenant_id
                 WHERE fpv.tenant_id=? AND fpv.id=?
                 LIMIT 1
                """, (rs, row) -> policy(rs), bin(tenantId), bin(fieldPolicyVersionId));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Optional<VisitPolicy> findActiveVisitPolicy(UUID tenantId, UUID salesProfileId,
                                                                 UUID cityOrgId, Instant at) {
        List<VisitPolicy> rows = jdbc.query("""
                SELECT fpv.id, fp.policy_code, fp.policy_name, fpv.version_no, fpv.publish_status,
                       fpv.require_assigned_target, fpv.allow_prospect_target,
                       fpv.check_in_radius_meters, fpv.minimum_dwell_minutes, fpv.required_photo_count,
                       fpv.recording_enabled, fpv.minimum_recording_seconds, fpv.maximum_clip_gap_seconds
                  FROM sales_visit_policy_version fpv
                  JOIN sales_visit_policy fp ON fp.id=fpv.policy_id AND fp.tenant_id=fpv.tenant_id
                 WHERE fpv.tenant_id=? AND fpv.publish_status='PUBLISHED'
                   AND (fpv.effective_from IS NULL OR fpv.effective_from <= ?)
                   AND (fpv.effective_to IS NULL OR fpv.effective_to > ?)
                   AND (
                         NOT EXISTS (
                             SELECT 1 FROM sales_policy_scope scope_all
                              WHERE scope_all.tenant_id=fpv.tenant_id
                                AND scope_all.policy_type='VISIT'
                                AND scope_all.status='ACTIVE'
                                AND scope_all.effective_from <= ?
                                AND (scope_all.effective_to IS NULL OR scope_all.effective_to > ?)
                         )
                         OR EXISTS (
                             SELECT 1 FROM sales_policy_scope scope_match
                              WHERE scope_match.tenant_id=fpv.tenant_id
                                AND scope_match.policy_type='VISIT'
                                AND scope_match.policy_version_id=fpv.id
                                AND scope_match.status='ACTIVE'
                                AND scope_match.effective_from <= ?
                                AND (scope_match.effective_to IS NULL OR scope_match.effective_to > ?)
                                AND (
                                    (scope_match.scope_type='EMPLOYEE' AND scope_match.scope_id=?)
                                    OR (scope_match.scope_type='CITY' AND scope_match.scope_id=?)
                                    OR (scope_match.scope_type='ALL' AND scope_match.scope_id IS NULL)
                                    OR (scope_match.scope_type='TEAM' AND EXISTS (
                                        SELECT 1 FROM sales_team_member member
                                         WHERE member.tenant_id=scope_match.tenant_id
                                           AND member.team_id=scope_match.scope_id
                                           AND member.sales_profile_id=?
                                           AND member.status='ACTIVE'
                                           AND member.effective_from <= ?
                                           AND (member.effective_to IS NULL OR member.effective_to > ?)
                                    ))
                                )
                         )
                   )
                 ORDER BY fpv.version_no DESC
                 LIMIT 1
                """, (rs, row) -> visitPolicy(rs), bin(tenantId), timestamp(at), timestamp(at),
                timestamp(at), timestamp(at), timestamp(at), timestamp(at), bin(salesProfileId),
                bin(cityOrgId), bin(salesProfileId), timestamp(at), timestamp(at));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Optional<VisitPolicy> findVisitPolicy(UUID tenantId, UUID visitPolicyVersionId) {
        List<VisitPolicy> rows = jdbc.query("""
                SELECT fpv.id, fp.policy_code, fp.policy_name, fpv.version_no, fpv.publish_status,
                       fpv.require_assigned_target, fpv.allow_prospect_target,
                       fpv.check_in_radius_meters, fpv.minimum_dwell_minutes, fpv.required_photo_count,
                       fpv.recording_enabled, fpv.minimum_recording_seconds, fpv.maximum_clip_gap_seconds
                  FROM sales_visit_policy_version fpv
                  JOIN sales_visit_policy fp ON fp.id=fpv.policy_id AND fp.tenant_id=fpv.tenant_id
                 WHERE fpv.tenant_id=? AND fpv.id=?
                 LIMIT 1
                """, (rs, row) -> visitPolicy(rs), bin(tenantId), bin(visitPolicyVersionId));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Optional<StoreProjection> findStoreById(UUID tenantId, UUID storeId) {
        List<StoreProjection> rows = jdbc.query("""
                SELECT store_id, customer_id, customer_name, store_name, store_address,
                       longitude, latitude, store_status
                  FROM crm_store_projection
                 WHERE tenant_id=? AND store_id=? AND store_status='ACTIVE'
                 LIMIT 1
                """, (rs, row) -> new StoreProjection(uuid(rs, "store_id"), uuid(rs, "customer_id"),
                rs.getString("customer_name"), rs.getString("store_name"), rs.getString("store_address"),
                rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"), rs.getString("store_status")),
                bin(tenantId), bin(storeId));
        return rows.stream().findFirst();
    }

    @Override
    public boolean isStoreAssignedToProfile(UUID tenantId, UUID salesProfileId, UUID storeId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM crm_sales_assignment_projection assignment
                 WHERE assignment.tenant_id=? AND assignment.sales_profile_id=?
                   AND assignment.store_id=? AND assignment.status='ACTIVE'
                   AND assignment.effective_from <= UTC_TIMESTAMP(6)
                   AND (assignment.effective_to IS NULL OR assignment.effective_to > UTC_TIMESTAMP(6))
                """, Integer.class, bin(tenantId), bin(salesProfileId), bin(storeId));
        return count != null && count > 0;
    }

    @Override
    public boolean existsStore(UUID tenantId, UUID storeId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM crm_store_projection
                 WHERE tenant_id=? AND store_id=?
                """, Integer.class, bin(tenantId), bin(storeId));
        return count != null && count > 0;
    }

    @Override
    public List<VisitTarget> findAssignedStoreTargets(UUID tenantId, UUID salesProfileId,
                                                       String query, int limit, int offset) {
        String pattern = likePattern(query);
        return jdbc.query("""
                SELECT store.id AS projection_id, assignment.assignment_type, store.customer_id, store.store_id,
                       store.customer_name, store.store_name, store.store_address,
                       store.longitude, store.latitude, store.store_status,
                       store.source_version, store.source_updated_at
                  FROM crm_sales_assignment_projection assignment
                  JOIN crm_store_projection store
                    ON store.tenant_id=assignment.tenant_id AND store.store_id=assignment.store_id
                 WHERE assignment.tenant_id=? AND assignment.sales_profile_id=?
                   AND assignment.status='ACTIVE'
                   AND assignment.effective_from <= UTC_TIMESTAMP(6)
                   AND (assignment.effective_to IS NULL OR assignment.effective_to > UTC_TIMESTAMP(6))
                   AND store.store_status='ACTIVE'
                   AND (store.store_name LIKE ? OR COALESCE(store.customer_name,'') LIKE ?
                        OR COALESCE(store.store_address,'') LIKE ?)
                 ORDER BY store.store_name, store.store_id
                 LIMIT ? OFFSET ?
                """, (rs, row) -> target(rs), bin(tenantId), bin(salesProfileId),
                pattern, pattern, pattern, limit, offset);
    }

    @Override
    public long countAssignedStoreTargets(UUID tenantId, UUID salesProfileId, String query) {
        String pattern = likePattern(query);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM crm_sales_assignment_projection assignment
                  JOIN crm_store_projection store
                    ON store.tenant_id=assignment.tenant_id AND store.store_id=assignment.store_id
                 WHERE assignment.tenant_id=? AND assignment.sales_profile_id=?
                   AND assignment.status='ACTIVE'
                   AND assignment.effective_from <= UTC_TIMESTAMP(6)
                   AND (assignment.effective_to IS NULL OR assignment.effective_to > UTC_TIMESTAMP(6))
                   AND store.store_status='ACTIVE'
                   AND (store.store_name LIKE ? OR COALESCE(store.customer_name,'') LIKE ?
                        OR COALESCE(store.store_address,'') LIKE ?)
                """, Long.class, bin(tenantId), bin(salesProfileId), pattern, pattern, pattern);
        return count == null ? 0L : count;
    }

    private static FieldPolicy policy(ResultSet rs) throws SQLException {
        return new FieldPolicy(uuid(rs, "id"), rs.getString("policy_code"), rs.getString("policy_name"),
                rs.getInt("version_no"), rs.getString("publish_status"), rs.getString("timezone_id"),
                rs.getObject("business_day_cutoff", LocalTime.class),
                rs.getObject("check_in_window_start", LocalTime.class),
                rs.getObject("check_in_window_end", LocalTime.class),
                rs.getObject("check_out_window_start", LocalTime.class),
                rs.getObject("check_out_window_end", LocalTime.class),
                rs.getInt("standard_work_minutes"), rs.getInt("minimum_work_minutes"),
                rs.getBoolean("require_check_out"), rs.getBoolean("allow_adjustment"),
                integerValue(rs.getObject("adjustment_deadline_hours")), rs.getBoolean("location_enabled"),
                rs.getInt("location_interval_minutes"), rs.getBigDecimal("minimum_location_accuracy_meters"),
                rs.getInt("offline_upload_deadline_minutes"), instant(rs.getTimestamp("effective_from")));
    }

    private static VisitTarget target(ResultSet rs) throws SQLException {
        return new VisitTarget(uuid(rs, "projection_id"), rs.getString("assignment_type"),
                uuid(rs, "customer_id"), uuid(rs, "store_id"), rs.getString("customer_name"),
                rs.getString("store_name"), rs.getString("store_address"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getString("store_status"), rs.getLong("source_version"),
                instant(rs.getTimestamp("source_updated_at")));
    }

    private static VisitPolicy visitPolicy(ResultSet rs) throws SQLException {
        return new VisitPolicy(uuid(rs, "id"), rs.getString("policy_code"), rs.getString("policy_name"),
                rs.getInt("version_no"), rs.getString("publish_status"),
                rs.getBoolean("require_assigned_target"), rs.getBoolean("allow_prospect_target"),
                rs.getInt("check_in_radius_meters"), rs.getInt("minimum_dwell_minutes"),
                rs.getInt("required_photo_count"), rs.getBoolean("recording_enabled"),
                rs.getInt("minimum_recording_seconds"), rs.getInt("maximum_clip_gap_seconds"));
    }

    private static Integer integerValue(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return SalesUuidCodec.decode(rs.getBytes(column));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }

    private static String likePattern(String query) {
        if (query == null || query.isBlank()) return "%";
        String normalized = query.trim().substring(0, Math.min(query.trim().length(), 128));
        return "%" + normalized.replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
