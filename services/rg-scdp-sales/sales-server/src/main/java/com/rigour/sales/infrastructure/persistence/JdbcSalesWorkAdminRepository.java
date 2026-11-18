package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldPolicyVersionView;
import com.rigour.sales.application.port.out.SalesWorkAdminRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 销售管理维护 JDBC 仓储；只写 Sales Work 自有 Schema。 */
@Repository
public class JdbcSalesWorkAdminRepository implements SalesWorkAdminRepository {

    private final JdbcTemplate jdbc;

    public JdbcSalesWorkAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public java.util.List<com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldTeamOption> fieldTeams(UUID tenantId) {
        return jdbc.query("SELECT id,team_name FROM sales_team WHERE tenant_id=? AND status='ACTIVE' ORDER BY team_name",
                (rs,n) -> new com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldTeamOption(SalesUuidCodec.decode(rs.getBytes("id")),rs.getString("team_name")),bin(tenantId));
    }

    @Override
    public List<FieldPolicyVersionView> fieldPolicies(UUID tenantId) {
        return jdbc.query("""
                SELECT v.*,p.policy_code,p.policy_name,s.scope_type,s.scope_id
                  FROM sales_field_policy_version v
                  JOIN sales_field_policy p ON p.tenant_id=v.tenant_id AND p.id=v.policy_id
                  LEFT JOIN sales_policy_scope s ON s.tenant_id=v.tenant_id
                    AND s.policy_version_id=v.id AND s.policy_type='FIELD' AND s.status='ACTIVE'
                 WHERE v.tenant_id=? AND p.status='ACTIVE'
                 ORDER BY v.created_at DESC,v.version_no DESC
                """, (r, n) -> new FieldPolicyVersionView(SalesUuidCodec.decode(r.getBytes("id")),
                    r.getInt("version_no"), r.getString("publish_status"), r.getTimestamp("created_at").toInstant(),
                    r.getTimestamp("effective_from") == null ? null : r.getTimestamp("effective_from").toInstant(),
                    new com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldPolicyCommand(
                        r.getString("policy_code"), r.getString("policy_name"), r.getString("timezone_id"),
                        r.getObject("business_day_cutoff", LocalTime.class),
                        r.getInt("standard_work_minutes"), r.getInt("minimum_work_minutes"), r.getBoolean("allow_resume"), r.getBoolean("work_hours_enabled"),
                        r.getBoolean("allow_adjustment"), r.getObject("adjustment_deadline_hours", Integer.class),
                        r.getBoolean("location_enabled"), r.getInt("location_interval_minutes"),
                        r.getBigDecimal("minimum_location_accuracy_meters"), r.getInt("offline_upload_deadline_minutes"),
                        r.getString("scope_type") == null ? "ALL" : r.getString("scope_type"),
                        SalesUuidCodec.decode(r.getBytes("scope_id")), true, r.getInt("version_no"))), bin(tenantId));
    }

    @Override
    public IdentityBindingRow upsertIdentityBinding(UUID tenantId, UUID platformUserId, UUID employeeId,
                                                    Instant now) {
        List<UUID> existing = jdbc.query("""
                SELECT id FROM sales_identity_projection
                 WHERE tenant_id=? AND platform_user_id=?
                """, (rs, row) -> SalesUuidCodec.decode(rs.getBytes("id")), bin(tenantId), bin(platformUserId));
        if (!existing.isEmpty()) {
            jdbc.update("""
                    UPDATE sales_identity_projection
                       SET employee_id=?, status='ACTIVE', effective_to=NULL,
                           source_version=source_version+1, updated_at=UTC_TIMESTAMP(6)
                     WHERE tenant_id=? AND platform_user_id=?
                    """, bin(employeeId), bin(tenantId), bin(platformUserId));
            return new IdentityBindingRow(existing.get(0), platformUserId, employeeId, "ACTIVE");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_identity_projection
                    (id, tenant_id, platform_user_id, employee_id, status, effective_from,
                     source_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(platformUserId), bin(employeeId), timestamp(now));
        return new IdentityBindingRow(id, platformUserId, employeeId, "ACTIVE");
    }

    @Override
    public SalesProfileRow upsertSalesProfile(UUID tenantId, UUID employeeId, String salesNo,
                                              UUID cityOrgId, Instant now) {
        List<UUID> existing = jdbc.query("""
                SELECT id FROM sales_profile WHERE tenant_id=? AND employee_id=?
                """, (rs, row) -> SalesUuidCodec.decode(rs.getBytes("id")), bin(tenantId), bin(employeeId));
        if (!existing.isEmpty()) {
            jdbc.update("""
                    UPDATE sales_profile
                       SET sales_no=?, city_org_id=?, status='ACTIVE', effective_to=NULL,
                           source_version=source_version+1, version=version+1, updated_at=UTC_TIMESTAMP(6)
                     WHERE tenant_id=? AND employee_id=?
                    """, salesNo, bin(cityOrgId), bin(tenantId), bin(employeeId));
            return new SalesProfileRow(existing.get(0), employeeId, salesNo, cityOrgId, "ACTIVE");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_profile
                    (id, tenant_id, employee_id, sales_no, city_org_id, status, effective_from,
                     source_version, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(id), bin(tenantId), bin(employeeId), salesNo, bin(cityOrgId), timestamp(now));
        return new SalesProfileRow(id, employeeId, salesNo, cityOrgId, "ACTIVE");
    }

    @Override
    public PolicyIdentity ensureFieldPolicy(UUID tenantId, String policyCode, String policyName, Instant now) {
        return ensurePolicy("sales_field_policy", tenantId, policyCode, policyName, now);
    }

    @Override
    public PolicyIdentity ensureVisitPolicy(UUID tenantId, String policyCode, String policyName, Instant now) {
        jdbc.update("INSERT INTO sales_visit_policy(id,tenant_id,policy_code,policy_name,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?,?) ON DUPLICATE KEY UPDATE id=id",
            bin(UUID.randomUUID()),bin(tenantId),policyCode,policyName,timestamp(now),timestamp(now));
        UUID id=jdbc.queryForObject("SELECT id FROM sales_visit_policy WHERE tenant_id=? AND policy_code=? FOR UPDATE",(rs,row)->SalesUuidCodec.decode(rs.getBytes(1)),bin(tenantId),policyCode);
        Integer version=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM sales_visit_policy_version WHERE tenant_id=? AND policy_id=?",Integer.class,bin(tenantId),bin(id));
        jdbc.update("UPDATE sales_visit_policy SET policy_name=?,updated_at=? WHERE tenant_id=? AND id=?",policyName,timestamp(now),bin(tenantId),bin(id));
        return new PolicyIdentity(id,version+1);
    }

    private PolicyIdentity ensurePolicy(String table, UUID tenantId, String policyCode, String policyName,
                                        Instant now) {
        List<UUID> existing = jdbc.query(
                "SELECT id FROM " + table + " WHERE tenant_id=? AND policy_code=? FOR UPDATE",
                (rs, row) -> SalesUuidCodec.decode(rs.getBytes("id")), bin(tenantId), policyCode);
        UUID policyId;
        if (existing.isEmpty()) {
            policyId = UUID.randomUUID();
            jdbc.update("INSERT INTO " + table + """
                     (id, tenant_id, policy_code, policy_name, status, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, bin(policyId), bin(tenantId), policyCode, policyName);
        } else {
            policyId = existing.get(0);
            jdbc.update("UPDATE " + table + """
                       SET policy_name=?, version=version+1, updated_at=UTC_TIMESTAMP(6)
                     WHERE tenant_id=? AND id=?
                    """, policyName, bin(tenantId), bin(policyId));
        }
        String versionTable = table + "_version";
        Integer maxVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM " + versionTable + " WHERE tenant_id=? AND policy_id=?",
                Integer.class, bin(tenantId), bin(policyId));
        return new PolicyIdentity(policyId, (maxVersion == null ? 0 : maxVersion) + 1);
    }

    @Override
    public UUID insertFieldPolicyVersion(UUID tenantId, UUID policyId, int versionNo, boolean publish,
                                         String timezoneId, LocalTime businessDayCutoff,
                                         int standardWorkMinutes, int minimumWorkMinutes,
                                         boolean allowResume, boolean workHoursEnabled, boolean allowAdjustment,
                                         Integer adjustmentDeadlineHours, boolean locationEnabled,
                                         int locationIntervalMinutes, BigDecimal minimumLocationAccuracyMeters,
                                         int offlineUploadDeadlineMinutes, UUID actorId, Instant now, Instant effectiveFrom) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_field_policy_version
                    (id, tenant_id, policy_id, version_no, publish_status, timezone_id,
                     business_day_cutoff, standard_work_minutes,
                     minimum_work_minutes, allow_resume, work_hours_enabled, allow_adjustment,
                     adjustment_deadline_hours, location_enabled, location_interval_minutes,
                     minimum_location_accuracy_meters, offline_upload_deadline_minutes,
                     effective_from, approved_by, approved_at, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, bin(versionId), bin(tenantId), bin(policyId), versionNo,
                publish ? "PUBLISHED" : "DRAFT", timezoneId, businessDayCutoff,
                standardWorkMinutes, minimumWorkMinutes, allowResume ? 1 : 0, workHoursEnabled ? 1 : 0, allowAdjustment ? 1 : 0,
                adjustmentDeadlineHours, locationEnabled ? 1 : 0, locationIntervalMinutes,
                minimumLocationAccuracyMeters, offlineUploadDeadlineMinutes,
                publish ? timestamp(effectiveFrom) : null, publish ? bin(actorId) : null,
                publish ? timestamp(now) : null, bin(actorId));
        return versionId;
    }

    @Override
    public UUID insertVisitPolicyVersion(UUID tenantId, UUID policyId, int versionNo, boolean publish,
                                         boolean requireAssignedTarget, boolean allowProspectTarget,
                                         int checkInRadiusMeters, int minimumDwellMinutes,
                                         int requiredPhotoCount, boolean recordingEnabled,
                                         int minimumRecordingSeconds, int maximumClipGapSeconds,
                                         boolean aiAsrEnabled, boolean aiRelevanceEnabled,
                                         boolean aiDuplicateEnabled, BigDecimal aiAutoConfirmThreshold,
                                         UUID actorId, Instant now) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_visit_policy_version
                    (id, tenant_id, policy_id, version_no, publish_status, require_assigned_target,
                     allow_prospect_target, check_in_radius_meters, minimum_dwell_minutes,
                     required_photo_count, recording_enabled, minimum_recording_seconds,
                     maximum_clip_gap_seconds, ai_asr_enabled, ai_relevance_enabled,
                     ai_duplicate_enabled, ai_auto_confirm_threshold, effective_from,
                     approved_by, approved_at, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, bin(versionId), bin(tenantId), bin(policyId), versionNo,
                publish ? "PUBLISHED" : "DRAFT", requireAssignedTarget ? 1 : 0, allowProspectTarget ? 1 : 0,
                checkInRadiusMeters, minimumDwellMinutes, requiredPhotoCount, recordingEnabled ? 1 : 0,
                minimumRecordingSeconds, maximumClipGapSeconds, aiAsrEnabled ? 1 : 0,
                aiRelevanceEnabled ? 1 : 0, aiDuplicateEnabled ? 1 : 0, aiAutoConfirmThreshold,
                publish ? timestamp(now) : null, publish ? bin(actorId) : null,
                publish ? timestamp(now) : null, bin(actorId));
        return versionId;
    }

    @Override public void saveVisitCaptureRules(UUID tenantId,UUID versionId,com.rigour.sales.api.v1.model.SalesVisitPolicyModels.CaptureRules rules) {
        jdbc.update("UPDATE sales_visit_policy_version SET capture_rules_json=? WHERE tenant_id=? AND id=?",
            rules==null ? null : new tools.jackson.databind.ObjectMapper().writeValueAsString(rules),bin(tenantId),bin(versionId));
    }

    @Override
    public void insertPolicyScope(UUID tenantId, String policyType, UUID policyVersionId, String scopeType,
                                  UUID scopeId, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO sales_policy_scope
                    (id, tenant_id, policy_type, policy_version_id, scope_type, scope_id, priority,
                     effective_from, effective_to, exception_reason, status, version,
                     created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, 'ACTIVE', 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(UUID.randomUUID()), bin(tenantId), policyType, bin(policyVersionId), scopeType,
                bin(scopeId), timestamp(now), bin(actorId));
    }

    @Override
    public StoreProjectionRow upsertStoreProjection(UUID tenantId, UUID storeId, UUID customerId,
                                                    String customerName, String storeName,
                                                    String storeAddress, BigDecimal longitude,
                                                    BigDecimal latitude, String storeStatus, Instant now) {
        List<UUID> existing = jdbc.query("""
                SELECT id FROM crm_store_projection WHERE tenant_id=? AND store_id=?
                """, (rs, row) -> SalesUuidCodec.decode(rs.getBytes("id")), bin(tenantId), bin(storeId));
        if (!existing.isEmpty()) {
            jdbc.update("""
                    UPDATE crm_store_projection
                       SET customer_id=?, customer_name=?, store_name=?, store_address=?,
                           longitude=?, latitude=?, store_status=?, source_version=source_version+1,
                           source_updated_at=?, projected_at=?
                     WHERE tenant_id=? AND store_id=?
                    """, bin(customerId), customerName, storeName, storeAddress, longitude, latitude,
                    storeStatus, timestamp(now), timestamp(now), bin(tenantId), bin(storeId));
        } else {
            jdbc.update("""
                    INSERT INTO crm_store_projection
                        (id, tenant_id, customer_id, store_id, customer_name, store_name,
                         store_address, longitude, latitude, store_status, source_version,
                         source_updated_at, projected_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, bin(UUID.randomUUID()), bin(tenantId), bin(customerId), bin(storeId),
                    customerName, storeName, storeAddress, longitude, latitude, storeStatus,
                    timestamp(now), timestamp(now));
        }
        return new StoreProjectionRow(storeId, customerId, storeName, storeStatus);
    }

    @Override
    public Optional<AssignmentRow> findActiveAssignment(UUID tenantId, UUID salesProfileId, UUID storeId,
                                                        String assignmentType) {
        List<AssignmentRow> rows = jdbc.query("""
                SELECT id, customer_id FROM crm_sales_assignment_projection
                 WHERE tenant_id=? AND sales_profile_id=? AND store_id=? AND assignment_type=?
                   AND status='ACTIVE'
                 ORDER BY effective_from DESC LIMIT 1
                """, (rs, row) -> new AssignmentRow(SalesUuidCodec.decode(rs.getBytes("id")),
                        salesProfileId, storeId, SalesUuidCodec.decode(rs.getBytes("customer_id")),
                        assignmentType, "ACTIVE"),
                bin(tenantId), bin(salesProfileId), bin(storeId), assignmentType);
        return rows.stream().findFirst();
    }

    @Override
    public AssignmentRow insertAssignment(UUID tenantId, UUID salesProfileId, UUID storeId, UUID customerId,
                                          String assignmentType, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm_sales_assignment_projection
                    (id, tenant_id, sales_profile_id, customer_id, store_id, assignment_type,
                     effective_from, effective_to, status, source_version, source_updated_at, projected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'ACTIVE', 1, ?, ?)
                """, bin(id), bin(tenantId), bin(salesProfileId), bin(customerId), bin(storeId),
                assignmentType, timestamp(now), timestamp(now), timestamp(now));
        return new AssignmentRow(id, salesProfileId, storeId, customerId, assignmentType, "ACTIVE");
    }

    @Override
    public boolean salesProfileExists(UUID tenantId, UUID salesProfileId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_profile WHERE tenant_id=? AND id=?
                """, Integer.class, bin(tenantId), bin(salesProfileId));
        return count != null && count > 0;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }
}
