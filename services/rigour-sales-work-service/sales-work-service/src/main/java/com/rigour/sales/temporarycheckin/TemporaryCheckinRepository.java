package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 临时打卡三表的 JDBC 仓储。所有 SQL 都强制带服务端固定租户，
 * 媒体列名只允许由服务层枚举生成。
 */
@Repository
public class TemporaryCheckinRepository {

    private final JdbcTemplate jdbc;

    public TemporaryCheckinRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SalespersonRow> findSalespersons(UUID tenantId, String city) {
        String cityClause = city == null ? "" : " AND city=?";
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId)));
        if (city != null) arguments.add(city);
        return jdbc.query("""
                SELECT id, source_record_id, name, city, position, employment_status, status, sort_order,
                       checkin_secret_hash, credential_version, credential_updated_at,
                       credential_updated_by, credential_update_reason
                  FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=? AND status='ACTIVE' AND employment_status<>'离职'
                """ + cityClause + " ORDER BY sort_order, name, id",
                (rs, row) -> salesperson(rs), arguments.toArray());
    }

    public Optional<SalespersonRow> findSalesperson(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, source_record_id, name, city, position, employment_status, status, sort_order,
                       checkin_secret_hash, credential_version, credential_updated_at,
                       credential_updated_by, credential_update_reason
                  FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=? AND id=? AND status='ACTIVE' AND employment_status<>'离职'
                 LIMIT 1
                """, (rs, row) -> salesperson(rs), bin(tenantId), bin(id)).stream().findFirst();
    }

    public int rotateSalespersonCredential(
            UUID tenantId,
            UUID salespersonId,
            String credentialHash,
            String actor,
            String reason,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE temp_sales_checkin_salesperson
                   SET checkin_secret_hash=?, credential_version=credential_version+1,
                       credential_updated_at=?, credential_updated_by=?, credential_update_reason=?,
                       updated_at=?
                 WHERE tenant_id=? AND id=? AND status='ACTIVE' AND employment_status<>'离职'
                """, credentialHash, timestamp(now), actor, reason, timestamp(now),
                bin(tenantId), bin(salespersonId));
        if (updated != 1) return 0;
        Integer version = jdbc.queryForObject("""
                SELECT credential_version
                  FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=? AND id=?
                """, Integer.class, bin(tenantId), bin(salespersonId));
        return version == null ? 0 : version;
    }

    public List<StoreRow> searchStores(UUID tenantId, String city, String escapedQuery, int limit) {
        return jdbc.query("""
                SELECT id, client_store_id, city, creator_salesperson_id, attribute, name, operating_status,
                       contact_name, contact_phone, area_range, facility_count, business_types_json,
                       intended_businesses_json, cooperation_intent, store_grade, tags_json,
                       longitude, latitude, accuracy_meters, location_captured_at, location_note,
                       location_verification_status, location_failure_reason, location_attempt_id,
                       source_poi_id, source_poi_name, source_poi_address,
                       source_poi_longitude, source_poi_latitude,
                       location_address, location_formatted_address, location_adcode,
                       amap_longitude, amap_latitude, geocode_status, geocode_error_code, geocoded_at,
                       status, created_at, updated_at
                  FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND (? IS NULL OR city=?) AND status='ACTIVE'
                   AND (name LIKE ? ESCAPE '=' OR contact_name LIKE ? ESCAPE '='
                        OR source_poi_address LIKE ? ESCAPE '=' OR location_address LIKE ? ESCAPE '='
                        OR location_formatted_address LIKE ? ESCAPE '=' OR location_note LIKE ? ESCAPE '=')
                 ORDER BY updated_at DESC, name, id
                 LIMIT ?
                """, (rs, row) -> store(rs), bin(tenantId), city, city,
                "%" + escapedQuery + "%", "%" + escapedQuery + "%", "%" + escapedQuery + "%",
                "%" + escapedQuery + "%", "%" + escapedQuery + "%", "%" + escapedQuery + "%", limit);
    }

    public List<StoreRow> findActiveStores(UUID tenantId) {
        return jdbc.query(storeSelect() + """
                 WHERE tenant_id=? AND status='ACTIVE'
                """, (rs, row) -> store(rs), bin(tenantId));
    }

    public List<StoreCheckinAnchorRow> findFirstAcceptableSubmittedStoreAnchors(
            UUID tenantId, int maxAccuracyMeters) {
        return jdbc.query("""
                SELECT store_id, longitude, latitude, accuracy_meters, location_captured_at
                  FROM (
                        SELECT store_id, longitude, latitude, accuracy_meters, location_captured_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY store_id
                                   ORDER BY submitted_at ASC, id ASC
                               ) AS anchor_rank
                          FROM temp_sales_checkin_submission
                         WHERE tenant_id=? AND status='SUBMITTED'
                           AND location_verification_status IN ('LEGACY', 'VERIFIED')
                           AND longitude IS NOT NULL AND latitude IS NOT NULL
                           AND longitude BETWEEN -180 AND 180
                           AND latitude BETWEEN -90 AND 90
                           AND (longitude<>0 OR latitude<>0)
                           AND accuracy_meters IS NOT NULL
                           AND accuracy_meters>=0 AND accuracy_meters<=?
                           AND submitted_at IS NOT NULL
                       ) ranked
                 WHERE anchor_rank=1
                """, (rs, row) -> storeCheckinAnchor(rs), bin(tenantId), maxAccuracyMeters);
    }

    public Optional<StoreCheckinAnchorRow> findFirstAcceptableSubmittedStoreAnchor(
            UUID tenantId, UUID storeId, int maxAccuracyMeters) {
        return jdbc.query("""
                SELECT store_id, longitude, latitude, accuracy_meters, location_captured_at
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND store_id=? AND status='SUBMITTED'
                   AND location_verification_status IN ('LEGACY', 'VERIFIED')
                   AND longitude IS NOT NULL AND latitude IS NOT NULL
                   AND longitude BETWEEN -180 AND 180
                   AND latitude BETWEEN -90 AND 90
                   AND (longitude<>0 OR latitude<>0)
                   AND accuracy_meters IS NOT NULL
                   AND accuracy_meters>=0 AND accuracy_meters<=?
                   AND submitted_at IS NOT NULL
                 ORDER BY submitted_at ASC, id ASC
                 LIMIT 1
                """, (rs, row) -> storeCheckinAnchor(rs),
                bin(tenantId), bin(storeId), maxAccuracyMeters).stream().findFirst();
    }

    public Optional<StoreRow> findStore(UUID tenantId, UUID id) {
        return jdbc.query(storeSelect() + " WHERE tenant_id=? AND id=? AND status='ACTIVE' LIMIT 1",
                (rs, row) -> store(rs), bin(tenantId), bin(id)).stream().findFirst();
    }

    public Optional<StoreRow> findStoreByClientId(UUID tenantId, UUID clientStoreId) {
        return jdbc.query(storeSelect() + " WHERE tenant_id=? AND client_store_id=? LIMIT 1",
                (rs, row) -> store(rs), bin(tenantId), bin(clientStoreId)).stream().findFirst();
    }

    public Optional<StoreRow> findStoreByClientIdForUpdate(UUID tenantId, UUID clientStoreId) {
        return jdbc.query(storeSelect() + " WHERE tenant_id=? AND client_store_id=? LIMIT 1 FOR UPDATE",
                (rs, row) -> store(rs), bin(tenantId), bin(clientStoreId)).stream().findFirst();
    }

    public Optional<StoreRow> findStoreBySourcePoiId(UUID tenantId, String sourcePoiId) {
        return jdbc.query(storeSelect() + """
                 WHERE tenant_id=? AND source_poi_id=?
                 ORDER BY CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END, updated_at DESC, id
                 LIMIT 1
                """, (rs, row) -> store(rs), bin(tenantId), sourcePoiId).stream().findFirst();
    }

    public Optional<StoreRow> findStoreBySourcePoiIdForUpdate(UUID tenantId, String sourcePoiId) {
        return jdbc.query(storeSelect() + """
                 WHERE tenant_id=? AND source_poi_id=?
                 ORDER BY CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END, updated_at DESC, id
                 LIMIT 1 FOR UPDATE
                """, (rs, row) -> store(rs), bin(tenantId), sourcePoiId).stream().findFirst();
    }

    public void insertStore(StoreWrite row) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, source_poi_id, source_poi_name, source_poi_address,
                     source_poi_longitude, source_poi_latitude, city, creator_salesperson_id, attribute, name,
                     operating_status, contact_name, contact_phone, area_range, facility_count,
                     business_types_json, intended_businesses_json, cooperation_intent, store_grade,
                     tags_json, longitude, latitude, accuracy_meters, location_captured_at, location_note,
                     location_verification_status, location_failure_reason, location_attempt_id,
                     location_address, location_formatted_address, location_adcode,
                     amap_longitude, amap_latitude, geocode_status, geocode_error_code, geocoded_at,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?,
                        CAST(? AS JSON), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, bin(row.id()), bin(row.tenantId()), bin(row.clientStoreId()), row.sourcePoiId(),
                row.sourcePoiName(), row.sourcePoiAddress(), row.sourcePoiLongitude(), row.sourcePoiLatitude(), row.city(),
                bin(row.creatorSalespersonId()), row.attribute(), row.name(), row.operatingStatus(),
                row.contactName(), row.contactPhone(), row.areaRange(), row.facilityCount(),
                row.businessTypesJson(), row.intendedBusinessesJson(), row.cooperationIntent(),
                row.storeGrade(), row.tagsJson(), row.longitude(), row.latitude(), row.accuracyMeters(),
                timestamp(row.locationCapturedAt()), row.locationNote(), row.locationVerificationStatus(),
                row.locationFailureReason(), bin(row.locationAttemptId()), row.geocode().address(),
                row.geocode().formattedAddress(), row.geocode().adcode(), row.geocode().amapLongitude(),
                row.geocode().amapLatitude(), row.geocode().status(), row.geocode().errorCode(),
                timestamp(row.geocode().geocodedAt()), timestamp(row.now()), timestamp(row.now()));
    }

    public int completeStoreProfile(StoreWrite row) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET source_poi_name=?, source_poi_address=?,
                       source_poi_longitude=?, source_poi_latitude=?,
                       attribute=?, name=?, operating_status=?, contact_name=?, contact_phone=?,
                       area_range=?, facility_count=?, business_types_json=CAST(? AS JSON),
                       intended_businesses_json=CAST(? AS JSON), cooperation_intent=?, store_grade=?,
                       tags_json=CAST(? AS JSON), longitude=?, latitude=?, accuracy_meters=?,
                       location_captured_at=?, location_note=?, location_verification_status=?,
                       location_failure_reason=?, location_attempt_id=?, location_address=?,
                       location_formatted_address=?, location_adcode=?, amap_longitude=?, amap_latitude=?,
                       geocode_status=?, geocode_error_code=?, geocoded_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND status='ACTIVE' AND source_poi_id=?
                """, row.sourcePoiName(), row.sourcePoiAddress(), row.sourcePoiLongitude(),
                row.sourcePoiLatitude(), row.attribute(), row.name(), row.operatingStatus(),
                row.contactName(), row.contactPhone(), row.areaRange(), row.facilityCount(),
                row.businessTypesJson(), row.intendedBusinessesJson(), row.cooperationIntent(),
                row.storeGrade(), row.tagsJson(), row.longitude(), row.latitude(), row.accuracyMeters(),
                timestamp(row.locationCapturedAt()), row.locationNote(), row.locationVerificationStatus(),
                row.locationFailureReason(), bin(row.locationAttemptId()), row.geocode().address(),
                row.geocode().formattedAddress(), row.geocode().adcode(), row.geocode().amapLongitude(),
                row.geocode().amapLatitude(), row.geocode().status(), row.geocode().errorCode(),
                timestamp(row.geocode().geocodedAt()), timestamp(row.now()), bin(row.tenantId()),
                bin(row.id()), row.sourcePoiId());
    }

    public int upgradeUnverifiedStoreLocation(StoreWrite row) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET source_poi_id=?, source_poi_name=?, source_poi_address=?,
                       source_poi_longitude=?, source_poi_latitude=?,
                       attribute=?, name=?, operating_status=?, contact_name=?, contact_phone=?,
                       area_range=?, facility_count=?, business_types_json=CAST(? AS JSON),
                       intended_businesses_json=CAST(? AS JSON), cooperation_intent=?, store_grade=?,
                       tags_json=CAST(? AS JSON), longitude=?, latitude=?, accuracy_meters=?,
                       location_captured_at=?, location_note=?, location_verification_status=?,
                       location_failure_reason=?, location_attempt_id=?, location_address=?,
                       location_formatted_address=?, location_adcode=?, amap_longitude=?, amap_latitude=?,
                       geocode_status=?, geocode_error_code=?, geocoded_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND client_store_id=? AND status='ACTIVE'
                   AND location_verification_status='UNVERIFIED'
                """, row.sourcePoiId(), row.sourcePoiName(), row.sourcePoiAddress(),
                row.sourcePoiLongitude(), row.sourcePoiLatitude(), row.attribute(), row.name(),
                row.operatingStatus(), row.contactName(), row.contactPhone(), row.areaRange(),
                row.facilityCount(), row.businessTypesJson(), row.intendedBusinessesJson(),
                row.cooperationIntent(), row.storeGrade(), row.tagsJson(), row.longitude(), row.latitude(),
                row.accuracyMeters(), timestamp(row.locationCapturedAt()), row.locationNote(),
                row.locationVerificationStatus(), row.locationFailureReason(), bin(row.locationAttemptId()),
                row.geocode().address(), row.geocode().formattedAddress(), row.geocode().adcode(),
                row.geocode().amapLongitude(), row.geocode().amapLatitude(), row.geocode().status(),
                row.geocode().errorCode(), timestamp(row.geocode().geocodedAt()), timestamp(row.now()),
                bin(row.tenantId()), bin(row.id()), bin(row.clientStoreId()));
    }

    public Optional<SubmissionRow> findSubmissionByClientId(UUID tenantId, UUID clientSubmissionId) {
        return jdbc.query(submissionSelect() + " WHERE tenant_id=? AND client_submission_id=? AND deletion_state='NONE' LIMIT 1",
                (rs, row) -> submission(rs), bin(tenantId), bin(clientSubmissionId)).stream().findFirst();
    }

    public RiskHistory findRiskHistory(
            UUID tenantId,
            UUID salespersonId,
            String deviceTokenHash,
            String ipHash,
            String ipNetworkHash,
            Instant now) {
        RiskMetric deviceSalespersons = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT salesperson_id) AS metric_count,
                       COALESCE(MAX(salesperson_id=?), 0) AS current_seen
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND status='SUBMITTED' AND submitted_at>=?
                   AND device_token_hash=?
                """, TemporaryCheckinRepository::riskMetric, bin(salespersonId), bin(tenantId),
                timestamp(now.minus(Duration.ofDays(30))), deviceTokenHash);
        RiskMetric salespersonDevices = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT device_token_hash) AS metric_count,
                       COALESCE(MAX(device_token_hash=?), 0) AS current_seen
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND status='SUBMITTED' AND submitted_at>=?
                   AND salesperson_id=? AND device_token_hash IS NOT NULL
                """, TemporaryCheckinRepository::riskMetric, deviceTokenHash, bin(tenantId),
                timestamp(now.minus(Duration.ofDays(1))), bin(salespersonId));
        RiskMetric salespersonNetworks = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT submitted_ip_network_hash) AS metric_count,
                       COALESCE(MAX(submitted_ip_network_hash=?), 0) AS current_seen
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND status='SUBMITTED' AND submitted_at>=?
                   AND salesperson_id=? AND submitted_ip_network_hash IS NOT NULL
                """, TemporaryCheckinRepository::riskMetric, ipNetworkHash, bin(tenantId),
                timestamp(now.minus(Duration.ofDays(1))), bin(salespersonId));
        RiskMetric ipSalespersons = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT salesperson_id) AS metric_count,
                       COALESCE(MAX(salesperson_id=?), 0) AS current_seen
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND status='SUBMITTED' AND submitted_at>=?
                   AND submitted_ip_hash=?
                """, TemporaryCheckinRepository::riskMetric, bin(salespersonId), bin(tenantId),
                timestamp(now.minus(Duration.ofMinutes(30))), ipHash);
        return new RiskHistory(
                deviceSalespersons.count(), deviceSalespersons.currentSeen(),
                salespersonDevices.count(), salespersonDevices.currentSeen(),
                salespersonNetworks.count(), salespersonNetworks.currentSeen(),
                ipSalespersons.count(), ipSalespersons.currentSeen());
    }

    public Optional<SubmissionRow> findSubmission(UUID tenantId, UUID id) {
        return jdbc.query(submissionSelect() + " WHERE tenant_id=? AND id=? AND deletion_state='NONE' LIMIT 1",
                (rs, row) -> submission(rs), bin(tenantId), bin(id)).stream().findFirst();
    }

    public Optional<SubmissionRow> findSubmissionForUpdate(UUID tenantId, UUID id) {
        return jdbc.query(submissionSelect() + " WHERE tenant_id=? AND id=? AND deletion_state='NONE' LIMIT 1 FOR UPDATE",
                (rs, row) -> submission(rs), bin(tenantId), bin(id)).stream().findFirst();
    }

    public void insertSubmission(SubmissionWrite row) {
        insertSubmission(row, true);
    }

    /** 新公开请求按实际布尔值持久化；旧内部导入调用继续保留其既有明确同意记录。 */
    public void insertSubmission(SubmissionWrite row, boolean privacyAccepted) {
        GeocodeWrite geocode = row.geocode();
        IdentityRiskWrite identity = row.identityRisk() == null
                ? IdentityRiskWrite.legacy(row.now()) : row.identityRisk();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission
                    (id, tenant_id, client_submission_id, submission_key_hash, status, city,
                     salesperson_id, salesperson_name_snapshot, store_id, store_name_snapshot,
                     customer_name, customer_phone, visit_result, longitude, latitude, accuracy_meters,
                     location_captured_at, location_note, location_verification_status,
                     location_failure_reason, location_attempt_id,
                     location_address, location_formatted_address, location_adcode, location_province,
                     location_city, location_district, location_township, amap_longitude, amap_latitude,
                     geocode_status, geocode_error_code, geocoded_at,
                     privacy_accepted, privacy_notice_version,
                     identity_method, identity_verified_at, credential_version, device_token_hash,
                     draft_ip_hash, draft_ip_network_hash, draft_ip_masked,
                     user_agent_hash, user_agent_summary, risk_level, risk_flags_json, risk_evaluated_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?)
                """, bin(row.id()), bin(row.tenantId()), bin(row.clientSubmissionId()), row.keyHash(), row.city(),
                bin(row.salespersonId()), row.salespersonName(), bin(row.storeId()), row.storeName(),
                row.customerName(), row.customerPhone(), row.visitResult(), row.longitude(), row.latitude(),
                row.accuracyMeters(), timestamp(row.locationCapturedAt()), row.locationNote(),
                row.locationVerificationStatus(), row.locationFailureReason(), bin(row.locationAttemptId()),
                geocode.address(), geocode.formattedAddress(), geocode.adcode(), geocode.province(),
                geocode.city(), geocode.district(), geocode.township(), geocode.amapLongitude(),
                geocode.amapLatitude(), geocode.status(), geocode.errorCode(), timestamp(geocode.geocodedAt()),
                privacyAccepted, row.privacyNoticeVersion(), identity.identityMethod(), timestamp(identity.identityVerifiedAt()),
                identity.credentialVersion(), identity.deviceTokenHash(), identity.ipHash(),
                identity.ipNetworkHash(), identity.ipMasked(), identity.userAgentHash(),
                identity.userAgentSummary(), identity.riskLevel(), identity.riskFlagsJson(),
                timestamp(identity.riskEvaluatedAt()),
                timestamp(row.now()), timestamp(row.now()));
    }

    public int updateMedia(UUID tenantId, UUID submissionId, String prefix, MediaWrite media, Instant now) {
        String sql = "UPDATE temp_sales_checkin_submission SET "
                + prefix + "object_key=?, " + prefix + "content_type=?, " + prefix + "size_bytes=?, "
                + prefix + "sha256=?, " + prefix + "original_filename=?, "
                + prefix + "deleted_at=NULL, " + prefix + "deleted_by=NULL, "
                + prefix + "deletion_reason=NULL, "
                + "updated_at=GREATEST(?, TIMESTAMPADD(MICROSECOND,1,updated_at)) "
                + "WHERE tenant_id=? AND id=? AND status='DRAFT' AND deletion_state='NONE'";
        return jdbc.update(sql, media.objectKey(), media.contentType(), media.sizeBytes(), media.sha256(),
                media.originalFilename(), timestamp(now), bin(tenantId), bin(submissionId));
    }

    /** 调用方先锁提交行；照片行和旧单图投影始终在同一事务中更新。 */
    public List<PhotoRow> photos(UUID tenant, UUID submission) {
        List<PhotoRow> rows=jdbc.query("""
                SELECT * FROM temp_sales_checkin_photo WHERE tenant_id=? AND submission_id=?
                ORDER BY (photo_id=submission_id) DESC, uploaded_at, photo_id
                """, (rs,n) -> photo(rs), bin(tenant), bin(submission));
        if(!rows.isEmpty()) return rows;
        return jdbc.query(legacyPhotoSelect()+" AND s.tenant_id=? AND s.id=?",(rs,n)->photo(rs),bin(tenant),bin(submission));
    }

    public java.util.Map<UUID,List<PhotoRow>> photosBatch(UUID tenant, List<UUID> submissions) {
        if (submissions.isEmpty()) return java.util.Map.of();
        List<Object> args = new ArrayList<>(); args.add(bin(tenant));
        submissions.forEach(id -> args.add(bin(id)));
        List<PhotoRow> rows=new ArrayList<>(jdbc.query("SELECT p.* FROM temp_sales_checkin_photo p JOIN temp_sales_checkin_submission s "
                + "ON s.tenant_id=p.tenant_id AND s.id=p.submission_id WHERE p.tenant_id=? "
                + "AND s.deletion_state='NONE' AND p.deleted_at IS NULL AND p.submission_id IN ("
                + String.join(",", java.util.Collections.nCopies(submissions.size(), "?"))
                + ") ORDER BY (p.photo_id=p.submission_id) DESC, p.uploaded_at, p.photo_id",
                (rs,n) -> photo(rs), args.toArray()));
        rows.addAll(jdbc.query(legacyPhotoSelect()+" AND s.tenant_id=? AND s.deletion_state='NONE' "
                +"AND s.storefront_photo_deleted_at IS NULL AND s.id IN ("
                +String.join(",",java.util.Collections.nCopies(submissions.size(),"?"))+")",(rs,n)->photo(rs),args.toArray()));
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(PhotoRow::submissionId));
    }

    /** 兼容旧容器在迁移后写入的单图，不把缺失的来源或上传时间猜成拍摄事实。 */
    private static String legacyPhotoSelect() {
        return """
                SELECT s.id AS submission_id,s.id AS photo_id,s.storefront_photo_object_key AS object_key,
                    s.storefront_photo_content_type AS content_type,s.storefront_photo_size_bytes AS size_bytes,
                    s.storefront_photo_sha256 AS sha256,s.storefront_photo_original_filename AS original_filename,
                    NULL AS uploaded_at,NULL AS capture_source,s.storefront_photo_deleted_at AS deleted_at,
                    s.storefront_photo_deleted_by AS deleted_by,s.storefront_photo_deletion_reason AS deletion_reason
                FROM temp_sales_checkin_submission s WHERE s.storefront_photo_object_key IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM temp_sales_checkin_photo p WHERE p.tenant_id=s.tenant_id AND p.submission_id=s.id)
                """;
    }

    private void materializeLegacyPhoto(UUID tenant,UUID submission) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_photo
                (tenant_id,submission_id,photo_id,object_key,content_type,size_bytes,sha256,original_filename,
                    deleted_at,deleted_by,deletion_reason)
                SELECT tenant_id,id,id,storefront_photo_object_key,storefront_photo_content_type,
                    storefront_photo_size_bytes,storefront_photo_sha256,storefront_photo_original_filename,
                    storefront_photo_deleted_at,storefront_photo_deleted_by,storefront_photo_deletion_reason
                FROM temp_sales_checkin_submission s WHERE tenant_id=? AND id=? AND storefront_photo_object_key IS NOT NULL
                AND NOT EXISTS(SELECT 1 FROM temp_sales_checkin_photo p WHERE p.tenant_id=s.tenant_id AND p.submission_id=s.id)
                """,bin(tenant),bin(submission));
    }

    public void savePhoto(UUID tenant, UUID submission, UUID photoId, MediaWrite media,
            String captureSource, Instant uploadedAt) {
        materializeLegacyPhoto(tenant,submission);
        jdbc.update("""
                INSERT INTO temp_sales_checkin_photo
                (tenant_id,submission_id,photo_id,object_key,content_type,size_bytes,sha256,
                 original_filename,uploaded_at,capture_source)
                VALUES (?,?,?,?,?,?,?,?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE object_key=incoming.object_key,content_type=incoming.content_type,
                    size_bytes=incoming.size_bytes,sha256=incoming.sha256,original_filename=incoming.original_filename,
                    uploaded_at=incoming.uploaded_at,capture_source=incoming.capture_source,
                    deleted_at=NULL,deleted_by=NULL,deletion_reason=NULL
                """,bin(tenant),bin(submission),bin(photoId),media.objectKey(),media.contentType(),media.sizeBytes(),
                media.sha256(),media.originalFilename(),timestamp(uploadedAt),captureSource);
        syncPhotoProjection(tenant,submission,uploadedAt);
    }

    public void markPhotoDeleted(UUID tenant, UUID submission, UUID photoId, String actor, String reason, Instant now) {
        materializeLegacyPhoto(tenant,submission);
        jdbc.update("""
                UPDATE temp_sales_checkin_photo SET deleted_at=?,deleted_by=?,deletion_reason=?
                WHERE tenant_id=? AND submission_id=? AND photo_id=? AND deleted_at IS NULL
                """,timestamp(now),actor,reason,bin(tenant),bin(submission),bin(photoId));
        syncPhotoProjection(tenant,submission,now);
    }

    private void syncPhotoProjection(UUID tenant, UUID submission, Instant now) {
        List<PhotoRow> rows=photos(tenant,submission);
        PhotoRow first=rows.stream().filter(PhotoRow::available).findFirst().orElse(rows.isEmpty()?null:rows.getFirst());
        if(first==null) return;
        jdbc.update("""
                UPDATE temp_sales_checkin_submission SET storefront_photo_object_key=?,storefront_photo_content_type=?,
                    storefront_photo_size_bytes=?,storefront_photo_sha256=?,storefront_photo_original_filename=?,
                    storefront_photo_deleted_at=?,storefront_photo_deleted_by=?,storefront_photo_deletion_reason=?,
                    updated_at=GREATEST(?,TIMESTAMPADD(MICROSECOND,1,updated_at))
                WHERE tenant_id=? AND id=? AND deletion_state='NONE'
                """,first.objectKey(),first.contentType(),first.sizeBytes(),first.sha256(),first.originalFilename(),
                timestamp(first.deletedAt()),first.deletedBy(),first.deletionReason(),timestamp(now),bin(tenant),bin(submission));
    }

    private static PhotoRow photo(ResultSet rs) throws SQLException {
        return new PhotoRow(uuid(rs,"submission_id"),uuid(rs,"photo_id"),rs.getString("object_key"),
                rs.getString("content_type"),rs.getLong("size_bytes"),rs.getString("sha256"),
                rs.getString("original_filename"),instant(rs,"uploaded_at"),rs.getString("capture_source"),
                instant(rs,"deleted_at"),rs.getString("deleted_by"),rs.getString("deletion_reason"));
    }

    public int updateMediaIfRevision(
            UUID tenantId, UUID submissionId, String prefix, MediaWrite media,
            Instant expectedUpdatedAt, Instant now) {
        String sql = "UPDATE temp_sales_checkin_submission SET "
                + prefix + "object_key=?, " + prefix + "content_type=?, " + prefix + "size_bytes=?, "
                + prefix + "sha256=?, " + prefix + "original_filename=?, "
                + prefix + "deleted_at=NULL, " + prefix + "deleted_by=NULL, "
                + prefix + "deletion_reason=NULL, "
                + "updated_at=GREATEST(?, TIMESTAMPADD(MICROSECOND,1,updated_at)) "
                + "WHERE tenant_id=? AND id=? AND status IN ('DRAFT','SUBMITTED') AND deletion_state='NONE' "
                + "AND updated_at=?";
        return jdbc.update(sql, media.objectKey(), media.contentType(), media.sizeBytes(), media.sha256(),
                media.originalFilename(), timestamp(now), bin(tenantId), bin(submissionId),
                timestamp(expectedUpdatedAt));
    }

    public int clearDraftMedia(
            UUID tenantId, UUID submissionId, String prefix, String expectedObjectKey, Instant now) {
        String sql = "UPDATE temp_sales_checkin_submission SET "
                + prefix + "object_key=NULL, " + prefix + "content_type=NULL, "
                + prefix + "size_bytes=NULL, " + prefix + "sha256=NULL, "
                + prefix + "original_filename=NULL, "
                + "updated_at=GREATEST(?, TIMESTAMPADD(MICROSECOND,1,updated_at)) "
                + "WHERE tenant_id=? AND id=? AND status='DRAFT' AND deletion_state='NONE' AND "
                + prefix + "object_key=?";
        return jdbc.update(sql, timestamp(now), bin(tenantId), bin(submissionId), expectedObjectKey);
    }

    public int touchDraftMediaMutation(UUID tenantId, UUID submissionId) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET updated_at=GREATEST(
                       UTC_TIMESTAMP(6), TIMESTAMPADD(MICROSECOND,1,updated_at))
                 WHERE tenant_id=? AND id=? AND status='DRAFT' AND deletion_state='NONE'
                """, bin(tenantId), bin(submissionId));
    }

    public int updateDraftAudioManifest(
            UUID tenantId, UUID submissionId, String manifestJson, int activeCount, long activeBytes,
            MediaWrite projection, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET audio_segments_json=CAST(? AS JSON),
                       audio_active_segment_count=?, audio_active_size_bytes=?,
                       audio_object_key=?, audio_content_type=?, audio_size_bytes=?, audio_sha256=?,
                       audio_original_filename=?, audio_deleted_at=NULL, audio_deleted_by=NULL,
                       audio_deletion_reason=NULL,
                       updated_at=GREATEST(?, TIMESTAMPADD(MICROSECOND,1,updated_at))
                 WHERE tenant_id=? AND id=? AND status='DRAFT' AND deletion_state='NONE'
                """, manifestJson, activeCount, activeBytes,
                projection == null ? null : projection.objectKey(),
                projection == null ? null : projection.contentType(),
                projection == null ? null : projection.sizeBytes(),
                projection == null ? null : projection.sha256(),
                projection == null ? null : projection.originalFilename(),
                timestamp(now), bin(tenantId), bin(submissionId));
    }

    public int updateDraftAudioManifestIfRevision(
            UUID tenantId, UUID submissionId, String manifestJson, int activeCount, long activeBytes,
            MediaWrite projection, Instant expectedUpdatedAt, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET audio_segments_json=CAST(? AS JSON),
                       audio_active_segment_count=?, audio_active_size_bytes=?,
                       audio_object_key=?, audio_content_type=?, audio_size_bytes=?, audio_sha256=?,
                       audio_original_filename=?, audio_deleted_at=NULL, audio_deleted_by=NULL,
                       audio_deletion_reason=NULL,
                       updated_at=GREATEST(?, TIMESTAMPADD(MICROSECOND,1,updated_at))
                 WHERE tenant_id=? AND id=? AND status IN ('DRAFT','SUBMITTED') AND deletion_state='NONE'
                   AND updated_at=?
                """, manifestJson, activeCount, activeBytes,
                projection == null ? null : projection.objectKey(),
                projection == null ? null : projection.contentType(),
                projection == null ? null : projection.sizeBytes(),
                projection == null ? null : projection.sha256(),
                projection == null ? null : projection.originalFilename(),
                timestamp(now), bin(tenantId), bin(submissionId), timestamp(expectedUpdatedAt));
    }

    public int updateAdminAudioManifest(
            UUID tenantId, UUID submissionId, String manifestJson, int activeCount, long activeBytes,
            MediaWrite projection, String deletedBy, String reason, Instant deletedAt) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET audio_segments_json=CAST(? AS JSON),
                       audio_active_segment_count=?, audio_active_size_bytes=?,
                       audio_object_key=?, audio_content_type=?, audio_size_bytes=?, audio_sha256=?,
                       audio_original_filename=?,
                       audio_deleted_at=?, audio_deleted_by=?, audio_deletion_reason=?,
                       updated_at=GREATEST(?, TIMESTAMPADD(MICROSECOND,1,updated_at))
                 WHERE tenant_id=? AND id=? AND deletion_state='NONE'
                """, manifestJson, activeCount, activeBytes,
                projection == null ? null : projection.objectKey(),
                projection == null ? null : projection.contentType(),
                projection == null ? null : projection.sizeBytes(),
                projection == null ? null : projection.sha256(),
                projection == null ? null : projection.originalFilename(),
                projection == null ? timestamp(deletedAt) : null,
                projection == null ? deletedBy : null,
                projection == null ? reason : null,
                timestamp(deletedAt), bin(tenantId), bin(submissionId));
    }

    public int complete(UUID tenantId, UUID submissionId, Instant submittedAt) {
        return complete(tenantId, submissionId, submittedAt, null);
    }

    public int complete(
            UUID tenantId,
            UUID submissionId,
            Instant submittedAt,
            CompletionRiskWrite risk) {
        if (risk == null) {
            return jdbc.update("""
                    UPDATE temp_sales_checkin_submission
                       SET status='SUBMITTED', submitted_at=?, updated_at=?
                     WHERE tenant_id=? AND id=? AND status='DRAFT' AND deletion_state='NONE'
                       AND storefront_photo_object_key IS NOT NULL
                       AND storefront_photo_deleted_at IS NULL
                    """, timestamp(submittedAt), timestamp(submittedAt), bin(tenantId), bin(submissionId));
        }
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET status='SUBMITTED', submitted_at=?,
                       submitted_ip_hash=?, submitted_ip_network_hash=?, submitted_ip_masked=?,
                       user_agent_hash=?, user_agent_summary=?, risk_level=?,
                       risk_flags_json=CAST(? AS JSON), risk_evaluated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND status='DRAFT' AND deletion_state='NONE'
                   AND storefront_photo_object_key IS NOT NULL
                   AND storefront_photo_deleted_at IS NULL
                """, timestamp(submittedAt), risk.ipHash(), risk.ipNetworkHash(), risk.ipMasked(),
                risk.userAgentHash(), risk.userAgentSummary(), risk.riskLevel(), risk.riskFlagsJson(),
                timestamp(risk.riskEvaluatedAt()), timestamp(submittedAt), bin(tenantId), bin(submissionId));
    }

    public List<ExportRow> export(UUID tenantId, Instant from, Instant toExclusive, String city,
                                  UUID salespersonId, String status, String visitType,
                                  String escapedQuery, int limit) {
        return export(tenantId, from, toExclusive, city, salespersonId, status, visitType, escapedQuery, limit, AdminReadOptions.defaults());
    }

    public List<ExportRow> export(UUID tenantId, Instant from, Instant toExclusive, String city,
                                  UUID salespersonId, String status, String visitType,
                                  String escapedQuery, int limit, AdminReadOptions options) {
        StringBuilder sql = new StringBuilder("""
                WITH visit_ranks AS (
                    SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY tenant_id, salesperson_id, store_id
                               ORDER BY submitted_at, id
                           ) AS visit_ordinal
                      FROM temp_sales_checkin_submission
                     WHERE tenant_id=? AND status='SUBMITTED'
                )
                SELECT s.id, s.client_submission_id, s.status, s.city, s.salesperson_id,
                       s.salesperson_name_snapshot, s.store_id, s.store_name_snapshot,
                       r.visit_ordinal,
                       customer_name, customer_phone, visit_result,
                       longitude, latitude, accuracy_meters, location_captured_at, location_note,
                       location_verification_status, location_failure_reason, location_attempt_id,
                       location_address, location_adcode,
                       identity_method, submitted_ip_masked, user_agent_summary,
                       risk_level, risk_flags_json,
                       storefront_photo_original_filename, storefront_photo_deleted_at,
                       wechat_screenshot_original_filename, wechat_screenshot_deleted_at,
                       audio_original_filename, audio_deleted_at, audio_segments_json,
                       transcription_status, transcript, summary_status, summary_text,
                       created_at, submitted_at
                  FROM temp_sales_checkin_submission s
                  LEFT JOIN visit_ranks r ON r.id=s.id
                 WHERE s.tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId), bin(tenantId)));
        appendAdminFilters(
                sql, arguments, from, toExclusive, city, salespersonId, status, visitType, escapedQuery, options);
        sql.append(options.orderBy()).append(" LIMIT ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> exportRow(rs), arguments.toArray());
    }

    public AdminSubmissionStats adminSubmissionStats(
            UUID tenantId, Instant from, Instant toExclusive, String city,
            UUID salespersonId, String status, String visitType, String escapedQuery) {
        return adminSubmissionStats(tenantId, from, toExclusive, city, salespersonId, status, visitType, escapedQuery, AdminReadOptions.defaults());
    }

    public AdminSubmissionStats adminSubmissionStats(
            UUID tenantId, Instant from, Instant toExclusive, String city,
            UUID salespersonId, String status, String visitType, String escapedQuery, AdminReadOptions options) {
        StringBuilder sql = new StringBuilder("""
                WITH visit_ranks AS (
                    SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY tenant_id, salesperson_id, store_id
                               ORDER BY submitted_at, id
                           ) AS visit_ordinal
                      FROM temp_sales_checkin_submission
                     WHERE tenant_id=? AND status='SUBMITTED'
                )
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN r.visit_ordinal=1 THEN 1 ELSE 0 END), 0)
                           AS first_visit_total,
                       COALESCE(SUM(CASE WHEN r.visit_ordinal>1 THEN 1 ELSE 0 END), 0)
                           AS revisit_total,
                       COALESCE(SUM(s.location_quality<>'GOOD'),0) AS location_attention_total,
                       COALESCE(SUM(s.review_status='PENDING'),0) AS review_pending_total,
                       COALESCE(SUM(s.audio_active_segment_count=0 AND (s.audio_object_key IS NULL OR s.audio_deleted_at IS NOT NULL)),0) AS missing_audio_total
                  FROM temp_sales_checkin_submission s
                  LEFT JOIN visit_ranks r ON r.id=s.id
                 WHERE s.tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId), bin(tenantId)));
        appendAdminFilters(
                sql, arguments, from, toExclusive, city, salespersonId, status, visitType, escapedQuery, options);
        return jdbc.queryForObject(sql.toString(), (rs, row) -> new AdminSubmissionStats(
                rs.getLong("total"), rs.getLong("first_visit_total"), rs.getLong("revisit_total"),
                rs.getLong("location_attention_total"),rs.getLong("review_pending_total"),rs.getLong("missing_audio_total")),
                arguments.toArray());
    }

    public List<AdminSubmissionRow> findAdminSubmissions(
            UUID tenantId, Instant from, Instant toExclusive, String city, UUID salespersonId,
            String status, String visitType, String escapedQuery, int offset, int limit) {
        return findAdminSubmissions(tenantId, from, toExclusive, city, salespersonId, status, visitType, escapedQuery, offset, limit, AdminReadOptions.defaults());
    }

    public List<AdminSubmissionRow> findAdminSubmissions(
            UUID tenantId, Instant from, Instant toExclusive, String city, UUID salespersonId,
            String status, String visitType, String escapedQuery, int offset, int limit, AdminReadOptions options) {
        StringBuilder sql = new StringBuilder("""
                WITH visit_ranks AS (
                    SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY tenant_id, salesperson_id, store_id
                               ORDER BY submitted_at, id
                           ) AS visit_ordinal
                      FROM temp_sales_checkin_submission
                     WHERE tenant_id=? AND status='SUBMITTED'
                )
                SELECT s.id, s.status, s.city, s.salesperson_id, s.salesperson_name_snapshot,
                       s.store_id, s.store_name_snapshot, r.visit_ordinal,
                       customer_name, customer_phone, visit_result,
                       longitude, latitude, accuracy_meters, location_captured_at, location_note,
                       location_verification_status, location_failure_reason, location_attempt_id,
                       location_address, location_adcode,
                       identity_method, submitted_ip_masked, user_agent_summary,
                       risk_level, risk_flags_json,
                       storefront_photo_object_key, storefront_photo_deleted_at,
                       wechat_screenshot_object_key, wechat_screenshot_deleted_at,
                       audio_object_key, audio_content_type, audio_size_bytes, audio_sha256,
                       audio_original_filename, audio_deleted_at, audio_deleted_by, audio_deletion_reason,
                       audio_segments_json, audio_active_segment_count,
                       transcription_status, transcript, transcription_error_code,
                       summary_status, summary_text, summary_error_code,
                       created_at, submitted_at
                  FROM temp_sales_checkin_submission s
                  LEFT JOIN visit_ranks r ON r.id=s.id
                 WHERE s.tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId), bin(tenantId)));
        appendAdminFilters(
                sql, arguments, from, toExclusive, city, salespersonId, status, visitType, escapedQuery, options);
        sql.append(options.orderBy()).append(" LIMIT ? OFFSET ?");
        arguments.add(limit);
        arguments.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> adminSubmission(rs), arguments.toArray());
    }

    public Optional<MediaReference> findMedia(
            UUID tenantId, UUID submissionId, String prefix, String city) {
        String sql = "SELECT " + prefix + "object_key AS object_key, "
                + prefix + "content_type AS content_type, " + prefix + "size_bytes AS size_bytes, "
                + prefix + "sha256 AS sha256, "
                + prefix + "original_filename AS original_filename, "
                + prefix + "deleted_at AS deleted_at, " + prefix + "deleted_by AS deleted_by, "
                + prefix + "deletion_reason AS deletion_reason "
                + "FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=? AND deletion_state='NONE'"
                + " AND " + prefix + "deleted_at IS NULL"
                + (city == null ? "" : " AND city=?") + " LIMIT 1";
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId), bin(submissionId)));
        if (city != null) arguments.add(city);
        return jdbc.query(sql, (rs, row) -> new MediaReference(rs.getString("object_key"),
                rs.getString("content_type"), nullableLong(rs, "size_bytes"),
                rs.getString("sha256"), rs.getString("original_filename"), instant(rs, "deleted_at"),
                rs.getString("deleted_by"), rs.getString("deletion_reason")), arguments.toArray()).stream()
                .filter(row -> row.objectKey() != null)
                .findFirst();
    }

    public MediaStorageStatsRow mediaStorageStats(UUID tenantId, String city) {
        String cityClause = city == null ? "" : " AND city=?";
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId)));
        if (city != null) arguments.add(city);
        MediaStorageStatsRow primary=jdbc.query("""
                SELECT
                  COALESCE(SUM(
                    (storefront_photo_object_key IS NOT NULL AND storefront_photo_deleted_at IS NULL)
                    + (wechat_screenshot_object_key IS NOT NULL AND wechat_screenshot_deleted_at IS NULL)
                    + IF(audio_active_segment_count > 0, audio_active_segment_count,
                         IF(audio_object_key IS NOT NULL AND audio_deleted_at IS NULL, 1, 0))
                  ), 0) AS active_files,
                  COALESCE(SUM(
                    IF(storefront_photo_object_key IS NOT NULL AND storefront_photo_deleted_at IS NULL,
                       storefront_photo_size_bytes, 0)
                    + IF(wechat_screenshot_object_key IS NOT NULL AND wechat_screenshot_deleted_at IS NULL,
                         wechat_screenshot_size_bytes, 0)
                    + IF(audio_active_segment_count > 0, audio_active_size_bytes,
                         IF(audio_object_key IS NOT NULL AND audio_deleted_at IS NULL,
                            COALESCE(audio_size_bytes, 0), 0))
                  ), 0) AS total_bytes,
                  COALESCE(SUM(
                    IF(storefront_photo_object_key IS NOT NULL AND storefront_photo_deleted_at IS NULL,
                       storefront_photo_size_bytes, 0)
                    + IF(wechat_screenshot_object_key IS NOT NULL AND wechat_screenshot_deleted_at IS NULL,
                         wechat_screenshot_size_bytes, 0)
                  ), 0) AS image_bytes,
                  COALESCE(SUM(
                    IF(audio_active_segment_count > 0, audio_active_size_bytes,
                       IF(audio_object_key IS NOT NULL AND audio_deleted_at IS NULL,
                          COALESCE(audio_size_bytes, 0), 0))
                  ), 0) AS audio_bytes,
                  MIN(IF(
                    (storefront_photo_object_key IS NOT NULL AND storefront_photo_deleted_at IS NULL)
                    OR (wechat_screenshot_object_key IS NOT NULL AND wechat_screenshot_deleted_at IS NULL)
                    OR audio_active_segment_count > 0
                    OR (audio_object_key IS NOT NULL AND audio_deleted_at IS NULL),
                    created_at, NULL
                  )) AS oldest_created_at
                FROM temp_sales_checkin_submission
                WHERE tenant_id=? AND deletion_state='NONE'
                """ + cityClause, (rs, row) -> new MediaStorageStatsRow(
                        rs.getLong("active_files"), rs.getLong("total_bytes"), rs.getLong("image_bytes"),
                        rs.getLong("audio_bytes"), instant(rs, "oldest_created_at")), arguments.toArray())
                .stream().findFirst().orElse(new MediaStorageStatsRow(0, 0, 0, 0, null));
        long[] additional=jdbc.query("""
                SELECT COUNT(*) AS files,COALESCE(SUM(p.size_bytes),0) AS bytes
                FROM temp_sales_checkin_photo p JOIN temp_sales_checkin_submission s
                    ON s.tenant_id=p.tenant_id AND s.id=p.submission_id
                WHERE s.tenant_id=? AND s.deletion_state='NONE' AND p.deleted_at IS NULL
                    AND p.object_key<>COALESCE(s.storefront_photo_object_key,'')
                """+(city==null?"":" AND s.city=?"),(rs,n)->new long[]{rs.getLong("files"),rs.getLong("bytes")},
                arguments.toArray()).getFirst();
        return new MediaStorageStatsRow(primary.activeFiles()+additional[0],primary.totalBytes()+additional[1],
                primary.imageBytes()+additional[1],primary.audioBytes(),primary.oldestCreatedAt());
    }

    public int markMediaDeleted(
            UUID tenantId, UUID submissionId, String prefix, String expectedObjectKey,
            String deletedBy, String reason, Instant deletedAt) {
        String extra = "audio_".equals(prefix)
                ? ", transcription_status='DELETED', transcript=NULL, transcription_error_code=NULL, "
                + "transcription_updated_at=?, summary_status='DELETED', summary_text=NULL, "
                + "summary_error_code=NULL, summary_updated_at=?"
                : "";
        String sql = "UPDATE temp_sales_checkin_submission SET " + prefix + "deleted_at=?, "
                + prefix + "deleted_by=?, " + prefix + "deletion_reason=?, "
                + "updated_at=GREATEST(?, TIMESTAMPADD(MICROSECOND,1,updated_at))" + extra
                + " WHERE tenant_id=? AND id=? AND " + prefix + "object_key=? AND "
                + prefix + "deleted_at IS NULL AND deletion_state='NONE'";
        if ("audio_".equals(prefix)) {
            return jdbc.update(sql, timestamp(deletedAt), deletedBy, reason, timestamp(deletedAt),
                    timestamp(deletedAt), timestamp(deletedAt), bin(tenantId), bin(submissionId), expectedObjectKey);
        }
        return jdbc.update(sql, timestamp(deletedAt), deletedBy, reason, timestamp(deletedAt),
                bin(tenantId), bin(submissionId), expectedObjectKey);
    }

    public int requestTranscription(
            UUID tenantId, UUID submissionId, String requiredPrivacyVersion, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET transcription_status='PENDING', transcription_error_code=NULL,
                       transcription_updated_at=?, summary_status='NOT_REQUESTED',
                       summary_text=NULL, summary_error_code=NULL, summary_model=NULL,
                       summary_updated_at=NULL, updated_at=?
                 WHERE tenant_id=? AND id=? AND status='SUBMITTED' AND deletion_state='NONE'
                   AND audio_object_key IS NOT NULL AND audio_deleted_at IS NULL
                   AND privacy_notice_version=?
                   AND transcription_status IN ('NOT_REQUESTED','FAILED','UNSUPPORTED')
                """, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId), requiredPrivacyVersion);
    }

    public int requestSummary(UUID tenantId, UUID submissionId, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET summary_status='PENDING', summary_error_code=NULL,
                       summary_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND status='SUBMITTED' AND deletion_state='NONE'
                   AND audio_object_key IS NOT NULL AND audio_deleted_at IS NULL
                   AND transcription_status='SUCCEEDED' AND transcript IS NOT NULL
                   AND summary_status='FAILED'
                """, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId));
    }

    public Optional<TranscriptionJob> findPendingTranscription(UUID tenantId) {
        return jdbc.query(transcriptionJobSelect() + """
                 WHERE tenant_id=? AND status='SUBMITTED' AND deletion_state='NONE'
                   AND audio_object_key IS NOT NULL AND audio_deleted_at IS NULL
                   AND transcription_status='PENDING'
                 ORDER BY transcription_updated_at, created_at, id
                 LIMIT 1
                """, (rs, row) -> transcriptionJob(rs), bin(tenantId)).stream().findFirst();
    }

    public int claimPendingTranscription(UUID tenantId, UUID submissionId, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET transcription_status='SUBMITTING', transcription_attempts=transcription_attempts+1,
                       transcription_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND transcription_status='PENDING' AND deletion_state='NONE'
                   AND audio_deleted_at IS NULL
                """, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId));
    }

    public int markTranscriptionProcessing(
            UUID tenantId, UUID submissionId, String taskId, String requestId, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET transcription_status='PROCESSING', asr_task_id=?, asr_request_id=?,
                       transcription_error_code=NULL, transcription_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND transcription_status='SUBMITTING' AND deletion_state='NONE'
                   AND audio_deleted_at IS NULL
                """, taskId, requestId, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId));
    }

    public List<TranscriptionJob> findProcessingTranscriptions(UUID tenantId, Instant checkedBefore, int limit) {
        return jdbc.query(transcriptionJobSelect() + """
                 WHERE tenant_id=? AND status='SUBMITTED' AND deletion_state='NONE'
                   AND audio_deleted_at IS NULL AND transcription_status='PROCESSING'
                   AND (transcription_updated_at IS NULL OR transcription_updated_at<=?)
                 ORDER BY transcription_updated_at, created_at, id
                 LIMIT ?
                """, (rs, row) -> transcriptionJob(rs), bin(tenantId), timestamp(checkedBefore), limit);
    }

    public int touchTranscription(UUID tenantId, UUID submissionId, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET transcription_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND transcription_status='PROCESSING' AND deletion_state='NONE'
                """, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId));
    }

    public int markTranscriptionSucceeded(
            UUID tenantId, UUID submissionId, String transcript, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET transcription_status='SUCCEEDED', transcript=?, transcription_error_code=NULL,
                       transcription_updated_at=?, summary_status='PENDING', summary_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND transcription_status='PROCESSING' AND deletion_state='NONE'
                   AND audio_deleted_at IS NULL
                """, transcript, timestamp(now), timestamp(now), timestamp(now),
                bin(tenantId), bin(submissionId));
    }

    public int markTranscriptionFailed(
            UUID tenantId, UUID submissionId, String expectedStatus, String errorCode, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET transcription_status='FAILED', transcription_error_code=?,
                       transcription_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND transcription_status=? AND deletion_state='NONE'
                """, errorCode, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId), expectedStatus);
    }

    public int markTranscriptionUnsupported(
            UUID tenantId, UUID submissionId, String expectedStatus, String errorCode, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET transcription_status='UNSUPPORTED', transcription_error_code=?,
                       transcription_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND transcription_status=? AND deletion_state='NONE'
                """, errorCode, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId), expectedStatus);
    }

    public Optional<TranscriptionJob> findPendingSummary(UUID tenantId) {
        return jdbc.query(transcriptionJobSelect() + """
                 WHERE tenant_id=? AND status='SUBMITTED' AND deletion_state='NONE' AND audio_deleted_at IS NULL
                   AND transcription_status='SUCCEEDED' AND summary_status='PENDING'
                 ORDER BY summary_updated_at, created_at, id
                 LIMIT 1
                """, (rs, row) -> transcriptionJob(rs), bin(tenantId)).stream().findFirst();
    }

    public int claimPendingSummary(UUID tenantId, UUID submissionId, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET summary_status='PROCESSING', summary_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND summary_status='PENDING' AND deletion_state='NONE'
                   AND transcription_status='SUCCEEDED' AND audio_deleted_at IS NULL
                """, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId));
    }

    public int markSummarySucceeded(
            UUID tenantId, UUID submissionId, String summary, String model, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET summary_status='SUCCEEDED', summary_text=?, summary_model=?,
                       summary_error_code=NULL, summary_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND summary_status='PROCESSING' AND deletion_state='NONE'
                   AND audio_deleted_at IS NULL
                """, summary, model, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId));
    }

    public int markSummaryFailed(
            UUID tenantId, UUID submissionId, String errorCode, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET summary_status='FAILED', summary_error_code=?, summary_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND summary_status='PROCESSING' AND deletion_state='NONE'
                """, errorCode, timestamp(now), timestamp(now), bin(tenantId), bin(submissionId));
    }

    public int recoverStuckAnalysis(UUID tenantId, Instant before, Instant now) {
        int transcription = jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET transcription_status='PENDING', transcription_error_code='RECOVERED_AFTER_RESTART',
                       transcription_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND transcription_status='SUBMITTING' AND deletion_state='NONE'
                   AND transcription_updated_at<? AND audio_deleted_at IS NULL
                """, timestamp(now), timestamp(now), bin(tenantId), timestamp(before));
        int summary = jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET summary_status='PENDING', summary_error_code='RECOVERED_AFTER_RESTART',
                       summary_updated_at=?, updated_at=?
                 WHERE tenant_id=? AND summary_status='PROCESSING' AND deletion_state='NONE'
                   AND summary_updated_at<? AND audio_deleted_at IS NULL
                """, timestamp(now), timestamp(now), bin(tenantId), timestamp(before));
        return transcription + summary;
    }

    private static void appendAdminFilters(
            StringBuilder sql, List<Object> arguments, Instant from, Instant toExclusive, String city,
            UUID salespersonId, String status, String visitType, String escapedQuery, AdminReadOptions options) {
        if (options.locationStatus() != null) {
            sql.append(" AND s.location_quality=?"); arguments.add(options.locationStatus());
        }
        if (options.reviewStatus() != null) {
            sql.append(" AND s.review_status=?"); arguments.add(options.reviewStatus());
        }
        if ("MISSING_AUDIO".equals(options.mediaStatus()))
            sql.append(" AND s.audio_active_segment_count=0 AND (s.audio_object_key IS NULL OR s.audio_deleted_at IS NOT NULL)");
        if ("HAS_AUDIO".equals(options.mediaStatus()))
            sql.append(" AND (s.audio_active_segment_count>0 OR (s.audio_object_key IS NOT NULL AND s.audio_deleted_at IS NULL))");
        if ("MISSING_PHOTO".equals(options.mediaStatus()))
            sql.append(" AND (s.storefront_photo_object_key IS NULL OR s.storefront_photo_deleted_at IS NOT NULL)");
        // 已提交记录按真正的拜访提交时间归属日期；草稿尚无 submitted_at，才回退创建时间。
        if (from != null) {
            sql.append(" AND COALESCE(s.submitted_at, s.created_at)>=?");
            arguments.add(timestamp(from));
        }
        if (toExclusive != null) {
            sql.append(" AND COALESCE(s.submitted_at, s.created_at)<?");
            arguments.add(timestamp(toExclusive));
        }
        if (city != null) { sql.append(" AND s.city=?"); arguments.add(city); }
        if (salespersonId != null) {
            sql.append(" AND s.salesperson_id=?");
            arguments.add(bin(salespersonId));
        }
        if (status != null) { sql.append(" AND s.status=?"); arguments.add(status); }
        if ("FIRST_VISIT".equals(visitType)) sql.append(" AND r.visit_ordinal=1");
        if ("REVISIT".equals(visitType)) sql.append(" AND r.visit_ordinal>1");
        if (escapedQuery != null) {
            String pattern = "%" + escapedQuery + "%";
            sql.append("""
                     AND (s.store_name_snapshot LIKE ? ESCAPE '='
                          OR s.customer_name LIKE ? ESCAPE '='
                          OR s.salesperson_name_snapshot LIKE ? ESCAPE '='
                          OR s.customer_phone LIKE ? ESCAPE '=')
                    """);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
    }

    private static String transcriptionJobSelect() {
        return """
                SELECT id, audio_object_key, audio_content_type, audio_size_bytes, audio_sha256,
                       audio_original_filename, audio_deleted_at, audio_deleted_by, audio_deletion_reason,
                       transcription_status, asr_task_id, asr_request_id, transcript,
                       transcription_error_code, transcription_attempts, transcription_updated_at,
                       summary_status, summary_text, summary_error_code, summary_model, summary_updated_at
                  FROM temp_sales_checkin_submission
                """;
    }

    private static TranscriptionJob transcriptionJob(ResultSet rs) throws SQLException {
        return new TranscriptionJob(uuid(rs, "id"),
                new MediaReference(rs.getString("audio_object_key"), rs.getString("audio_content_type"),
                        nullableLong(rs, "audio_size_bytes"), rs.getString("audio_sha256"),
                        rs.getString("audio_original_filename"), instant(rs, "audio_deleted_at"),
                        rs.getString("audio_deleted_by"), rs.getString("audio_deletion_reason")),
                rs.getString("transcription_status"), rs.getString("asr_task_id"),
                rs.getString("asr_request_id"), rs.getString("transcript"),
                rs.getString("transcription_error_code"), rs.getInt("transcription_attempts"),
                instant(rs, "transcription_updated_at"), rs.getString("summary_status"),
                rs.getString("summary_text"), rs.getString("summary_error_code"),
                rs.getString("summary_model"), instant(rs, "summary_updated_at"));
    }

    private static String storeSelect() {
        return """
                SELECT id, client_store_id, city, creator_salesperson_id, attribute, name, operating_status,
                       contact_name, contact_phone, area_range, facility_count, business_types_json,
                       intended_businesses_json, cooperation_intent, store_grade, tags_json,
                       longitude, latitude, accuracy_meters, location_captured_at, location_note,
                       location_verification_status, location_failure_reason, location_attempt_id,
                       source_poi_id, source_poi_name, source_poi_address,
                       source_poi_longitude, source_poi_latitude,
                       location_address, location_formatted_address, location_adcode,
                       amap_longitude, amap_latitude, geocode_status, geocode_error_code, geocoded_at,
                       status, created_at, updated_at
                  FROM temp_sales_checkin_store
                """;
    }

    private static String submissionSelect() {
        return """
                SELECT id, client_submission_id, submission_key_hash, status, city, salesperson_id,
                       salesperson_name_snapshot, store_id, store_name_snapshot, customer_name,
                       customer_phone, visit_result, longitude, latitude, accuracy_meters,
                       location_captured_at, location_note, location_verification_status,
                       location_failure_reason, location_attempt_id, privacy_accepted, privacy_notice_version,
                       identity_method, identity_verified_at, credential_version, device_token_hash,
                       draft_ip_hash, draft_ip_network_hash, draft_ip_masked,
                       submitted_ip_hash, submitted_ip_network_hash, submitted_ip_masked,
                       user_agent_hash, user_agent_summary, risk_level, risk_flags_json, risk_evaluated_at,
                       storefront_photo_object_key, storefront_photo_content_type,
                       storefront_photo_size_bytes, storefront_photo_sha256,
                       storefront_photo_original_filename, storefront_photo_deleted_at,
                       storefront_photo_deleted_by, storefront_photo_deletion_reason,
                       wechat_screenshot_object_key, wechat_screenshot_content_type,
                       wechat_screenshot_size_bytes, wechat_screenshot_sha256,
                       wechat_screenshot_original_filename, wechat_screenshot_deleted_at,
                       wechat_screenshot_deleted_by, wechat_screenshot_deletion_reason,
                       audio_object_key, audio_content_type, audio_size_bytes, audio_sha256,
                       audio_original_filename, audio_deleted_at, audio_deleted_by, audio_deletion_reason,
                       audio_segments_json, audio_active_segment_count, audio_active_size_bytes,
                       transcription_status, asr_task_id, asr_request_id, transcript,
                       transcription_error_code, transcription_attempts, transcription_updated_at,
                       summary_status, summary_text, summary_error_code, summary_model, summary_updated_at,
                       created_at, submitted_at, updated_at
                  FROM temp_sales_checkin_submission
                """;
    }

    private static SalespersonRow salesperson(ResultSet rs) throws SQLException {
        return new SalespersonRow(uuid(rs, "id"), rs.getString("source_record_id"), rs.getString("name"),
                rs.getString("city"), rs.getString("position"), rs.getString("employment_status"),
                rs.getString("status"), rs.getInt("sort_order"), rs.getString("checkin_secret_hash"),
                rs.getInt("credential_version"), instant(rs, "credential_updated_at"),
                rs.getString("credential_updated_by"), rs.getString("credential_update_reason"));
    }

    private static RiskMetric riskMetric(ResultSet rs, int row) throws SQLException {
        return new RiskMetric(rs.getLong("metric_count"), rs.getBoolean("current_seen"));
    }

    private static StoreRow store(ResultSet rs) throws SQLException {
        return new StoreRow(uuid(rs, "id"), uuid(rs, "client_store_id"), rs.getString("city"),
                uuid(rs, "creator_salesperson_id"), rs.getString("attribute"), rs.getString("name"),
                rs.getString("operating_status"), rs.getString("contact_name"),
                rs.getString("contact_phone"), rs.getString("area_range"), rs.getString("facility_count"),
                rs.getString("business_types_json"), rs.getString("intended_businesses_json"),
                rs.getString("cooperation_intent"), rs.getString("store_grade"), rs.getString("tags_json"),
                rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("accuracy_meters"), instant(rs, "location_captured_at"),
                rs.getString("location_note"), rs.getString("location_verification_status"),
                rs.getString("location_failure_reason"), uuid(rs, "location_attempt_id"),
                rs.getString("source_poi_id"), rs.getString("source_poi_name"),
                rs.getString("source_poi_address"), rs.getBigDecimal("source_poi_longitude"),
                rs.getBigDecimal("source_poi_latitude"), rs.getString("location_address"),
                rs.getString("location_formatted_address"), rs.getString("location_adcode"),
                rs.getBigDecimal("amap_longitude"), rs.getBigDecimal("amap_latitude"),
                rs.getString("geocode_status"), rs.getString("geocode_error_code"), instant(rs, "geocoded_at"),
                rs.getString("status"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static StoreCheckinAnchorRow storeCheckinAnchor(ResultSet rs) throws SQLException {
        return new StoreCheckinAnchorRow(uuid(rs, "store_id"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("accuracy_meters"),
                instant(rs, "location_captured_at"));
    }

    private static SubmissionRow submission(ResultSet rs) throws SQLException {
        return new SubmissionRow(uuid(rs, "id"), uuid(rs, "client_submission_id"),
                rs.getString("submission_key_hash"), rs.getString("status"), rs.getString("city"),
                uuid(rs, "salesperson_id"), rs.getString("salesperson_name_snapshot"),
                uuid(rs, "store_id"), rs.getString("store_name_snapshot"), rs.getString("customer_name"),
                rs.getString("customer_phone"), rs.getString("visit_result"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("accuracy_meters"),
                instant(rs, "location_captured_at"), rs.getString("location_note"),
                rs.getString("location_verification_status"), rs.getString("location_failure_reason"),
                uuid(rs, "location_attempt_id"),
                rs.getBoolean("privacy_accepted"), rs.getString("privacy_notice_version"),
                rs.getString("identity_method"), instant(rs, "identity_verified_at"),
                nullableInt(rs, "credential_version"), rs.getString("device_token_hash"),
                rs.getString("draft_ip_hash"), rs.getString("draft_ip_network_hash"),
                rs.getString("draft_ip_masked"), rs.getString("submitted_ip_hash"),
                rs.getString("submitted_ip_network_hash"), rs.getString("submitted_ip_masked"),
                rs.getString("user_agent_hash"), rs.getString("user_agent_summary"),
                rs.getString("risk_level"), rs.getString("risk_flags_json"), instant(rs, "risk_evaluated_at"),
                media(rs, "storefront_photo_"), media(rs, "wechat_screenshot_"), media(rs, "audio_"),
                rs.getString("audio_segments_json"), rs.getInt("audio_active_segment_count"),
                rs.getLong("audio_active_size_bytes"),
                rs.getString("transcription_status"), rs.getString("asr_task_id"),
                rs.getString("asr_request_id"), rs.getString("transcript"),
                rs.getString("transcription_error_code"), rs.getInt("transcription_attempts"),
                instant(rs, "transcription_updated_at"), rs.getString("summary_status"),
                rs.getString("summary_text"), rs.getString("summary_error_code"), rs.getString("summary_model"),
                instant(rs, "summary_updated_at"),
                instant(rs, "created_at"), instant(rs, "submitted_at"), instant(rs, "updated_at"));
    }

    private static MediaReference media(ResultSet rs, String prefix) throws SQLException {
        return new MediaReference(rs.getString(prefix + "object_key"), rs.getString(prefix + "content_type"),
                nullableLong(rs, prefix + "size_bytes"), rs.getString(prefix + "sha256"),
                rs.getString(prefix + "original_filename"), instant(rs, prefix + "deleted_at"),
                rs.getString(prefix + "deleted_by"), rs.getString(prefix + "deletion_reason"));
    }

    private static ExportRow exportRow(ResultSet rs) throws SQLException {
        return new ExportRow(uuid(rs, "id"), uuid(rs, "client_submission_id"), rs.getString("status"),
                rs.getString("city"), uuid(rs, "salesperson_id"), rs.getString("salesperson_name_snapshot"),
                uuid(rs, "store_id"), rs.getString("store_name_snapshot"), nullableLong(rs, "visit_ordinal"),
                rs.getString("customer_name"),
                rs.getString("customer_phone"), rs.getString("visit_result"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("accuracy_meters"),
                instant(rs, "location_captured_at"), rs.getString("location_note"),
                rs.getString("location_verification_status"), rs.getString("location_failure_reason"),
                uuid(rs, "location_attempt_id"),
                rs.getString("location_address"), rs.getString("location_adcode"),
                rs.getString("identity_method"), rs.getString("submitted_ip_masked"),
                rs.getString("user_agent_summary"), rs.getString("risk_level"),
                rs.getString("risk_flags_json"),
                rs.getString("storefront_photo_deleted_at") == null
                        ? rs.getString("storefront_photo_original_filename") : null,
                rs.getString("wechat_screenshot_deleted_at") == null
                        ? rs.getString("wechat_screenshot_original_filename") : null,
                rs.getString("audio_deleted_at") == null ? rs.getString("audio_original_filename") : null,
                rs.getString("audio_segments_json"),
                rs.getString("transcription_status"), rs.getString("transcript"),
                rs.getString("summary_status"), rs.getString("summary_text"),
                instant(rs, "created_at"), instant(rs, "submitted_at"));
    }

    private static AdminSubmissionRow adminSubmission(ResultSet rs) throws SQLException {
        return new AdminSubmissionRow(uuid(rs, "id"), rs.getString("status"), rs.getString("city"),
                uuid(rs, "salesperson_id"), rs.getString("salesperson_name_snapshot"),
                uuid(rs, "store_id"), rs.getString("store_name_snapshot"), nullableLong(rs, "visit_ordinal"),
                rs.getString("customer_name"),
                rs.getString("customer_phone"), rs.getString("visit_result"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("accuracy_meters"),
                instant(rs, "location_captured_at"), rs.getString("location_note"),
                rs.getString("location_verification_status"), rs.getString("location_failure_reason"),
                uuid(rs, "location_attempt_id"),
                rs.getString("location_address"), rs.getString("location_adcode"),
                rs.getString("identity_method"), rs.getString("submitted_ip_masked"),
                rs.getString("user_agent_summary"), rs.getString("risk_level"),
                rs.getString("risk_flags_json"),
                rs.getString("storefront_photo_object_key") != null
                        && instant(rs, "storefront_photo_deleted_at") == null,
                rs.getString("wechat_screenshot_object_key") != null
                        && instant(rs, "wechat_screenshot_deleted_at") == null,
                rs.getInt("audio_active_segment_count") > 0
                        || (rs.getString("audio_object_key") != null
                            && instant(rs, "audio_deleted_at") == null),
                media(rs, "audio_"), rs.getString("audio_segments_json"),
                rs.getInt("audio_active_segment_count"),
                instant(rs, "storefront_photo_deleted_at"), instant(rs, "wechat_screenshot_deleted_at"),
                instant(rs, "audio_deleted_at"), rs.getString("transcription_status"), rs.getString("transcript"),
                rs.getString("transcription_error_code"), rs.getString("summary_status"),
                rs.getString("summary_text"), rs.getString("summary_error_code"),
                instant(rs, "created_at"), instant(rs, "submitted_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return SalesUuidCodec.decode(rs.getBytes(column));
    }

    private static byte[] bin(UUID value) { return SalesUuidCodec.encode(value); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record SalespersonRow(
            UUID id, String sourceRecordId, String name, String city, String position,
            String employmentStatus, String status, int sortOrder, String checkinSecretHash,
            int credentialVersion, Instant credentialUpdatedAt, String credentialUpdatedBy,
            String credentialUpdateReason) { }

    public record RiskHistory(
            long deviceSalespersonCount,
            boolean deviceSeenForSalesperson,
            long salespersonDeviceCount,
            boolean salespersonSeenDevice,
            long salespersonNetworkCount,
            boolean salespersonSeenNetwork,
            long ipSalespersonCount,
            boolean ipSeenForSalesperson) { }

    private record RiskMetric(long count, boolean currentSeen) { }

    public record StoreRow(
            UUID id, UUID clientStoreId, String city, UUID creatorSalespersonId, String attribute, String name,
            String operatingStatus, String contactName, String contactPhone, String areaRange, String facilityCount,
            String businessTypesJson, String intendedBusinessesJson, String cooperationIntent, String storeGrade,
            String tagsJson, BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, String locationVerificationStatus,
            String locationFailureReason, UUID locationAttemptId, String sourcePoiId, String sourcePoiName,
            String sourcePoiAddress, BigDecimal sourcePoiLongitude, BigDecimal sourcePoiLatitude,
            String locationAddress, String locationFormattedAddress, String locationAdcode,
            BigDecimal amapLongitude, BigDecimal amapLatitude, String geocodeStatus, String geocodeErrorCode,
            Instant geocodedAt, String status, Instant createdAt, Instant updatedAt) { }

    public record StoreWrite(
            UUID id, UUID tenantId, UUID clientStoreId, String city, UUID creatorSalespersonId, String attribute,
            String name, String operatingStatus, String contactName, String contactPhone, String areaRange,
            String facilityCount, String businessTypesJson, String intendedBusinessesJson, String cooperationIntent,
            String storeGrade, String tagsJson, BigDecimal longitude, BigDecimal latitude,
            BigDecimal accuracyMeters, Instant locationCapturedAt, String locationNote,
            String locationVerificationStatus, String locationFailureReason, UUID locationAttemptId,
            String sourcePoiId, String sourcePoiName, String sourcePoiAddress,
            BigDecimal sourcePoiLongitude, BigDecimal sourcePoiLatitude, GeocodeWrite geocode, Instant now) { }

    /**
     * 门店自身无定位时的临时候选锚点。来自已提交的匿名自报拜访，
     * 只用于降低远程误打卡风险，不是可信门店位置或考勤证据。
     */
    public record StoreCheckinAnchorRow(
            UUID storeId, BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant capturedAt) { }

    public record MediaReference(
            String objectKey, String contentType, Long sizeBytes, String sha256, String originalFilename,
            Instant deletedAt, String deletedBy, String deletionReason) { }

    /** 独立照片事实，来源和上传时间在历史导入记录中可以未知。 */
    public record PhotoRow(UUID submissionId, UUID photoId, String objectKey, String contentType,
            long sizeBytes, String sha256, String originalFilename, Instant uploadedAt, String captureSource,
            Instant deletedAt, String deletedBy, String deletionReason) {
        public boolean available() { return objectKey != null && deletedAt == null; }
        public MediaReference media() { return new MediaReference(objectKey,contentType,sizeBytes,sha256,
                originalFilename,deletedAt,deletedBy,deletionReason); }
        public String mediaId() { return "photo-" + photoId; }
    }

    public record SubmissionRow(
            UUID id, UUID clientSubmissionId, String keyHash, String status, String city,
            UUID salespersonId, String salespersonName, UUID storeId, String storeName,
            String customerName, String customerPhone, String visitResult,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, String locationVerificationStatus,
            String locationFailureReason, UUID locationAttemptId,
            boolean privacyAccepted, String privacyNoticeVersion,
            String identityMethod, Instant identityVerifiedAt, Integer credentialVersion,
            String deviceTokenHash, String draftIpHash, String draftIpNetworkHash, String draftIpMasked,
            String submittedIpHash, String submittedIpNetworkHash, String submittedIpMasked,
            String userAgentHash, String userAgentSummary, String riskLevel, String riskFlagsJson,
            Instant riskEvaluatedAt,
            MediaReference storefrontPhoto, MediaReference wechatScreenshot, MediaReference audio,
            String audioSegmentsJson, int audioActiveSegmentCount, long audioActiveSizeBytes,
            String transcriptionStatus, String asrTaskId, String asrRequestId, String transcript,
            String transcriptionErrorCode, int transcriptionAttempts, Instant transcriptionUpdatedAt,
            String summaryStatus, String summaryText, String summaryErrorCode, String summaryModel,
            Instant summaryUpdatedAt,
            Instant createdAt, Instant submittedAt, Instant updatedAt) { }

    public record SubmissionWrite(
            UUID id, UUID tenantId, UUID clientSubmissionId, String keyHash, String city,
            UUID salespersonId, String salespersonName, UUID storeId, String storeName,
            String customerName, String customerPhone, String visitResult,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, String locationVerificationStatus,
            String locationFailureReason, UUID locationAttemptId, GeocodeWrite geocode,
            String privacyNoticeVersion, IdentityRiskWrite identityRisk, Instant now) {

        public SubmissionWrite(
                UUID id, UUID tenantId, UUID clientSubmissionId, String keyHash, String city,
                UUID salespersonId, String salespersonName, UUID storeId, String storeName,
                String customerName, String customerPhone, String visitResult,
                BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
                Instant locationCapturedAt, String locationNote, GeocodeWrite geocode,
                String privacyNoticeVersion, Instant now) {
            this(id, tenantId, clientSubmissionId, keyHash, city, salespersonId, salespersonName,
                    storeId, storeName, customerName, customerPhone, visitResult, longitude, latitude,
                    accuracyMeters, locationCapturedAt, locationNote, "LEGACY", null, null,
                    geocode, privacyNoticeVersion,
                    null, now);
        }
    }

    public record IdentityRiskWrite(
            String identityMethod,
            Instant identityVerifiedAt,
            Integer credentialVersion,
            String deviceTokenHash,
            String ipHash,
            String ipNetworkHash,
            String ipMasked,
            String userAgentHash,
            String userAgentSummary,
            String riskLevel,
            String riskFlagsJson,
            Instant riskEvaluatedAt) {
        static IdentityRiskWrite legacy(Instant now) {
            return new IdentityRiskWrite("LEGACY_ANONYMOUS", null, null, null,
                    null, null, null, null, null, "NONE", "[]", now);
        }
    }

    public record CompletionRiskWrite(
            String ipHash,
            String ipNetworkHash,
            String ipMasked,
            String userAgentHash,
            String userAgentSummary,
            String riskLevel,
            String riskFlagsJson,
            Instant riskEvaluatedAt) { }

    public record GeocodeWrite(
            String status, String address, String formattedAddress, String adcode,
            String province, String city, String district, String township,
            BigDecimal amapLongitude, BigDecimal amapLatitude, String errorCode, Instant geocodedAt) { }

    public record MediaWrite(
            String objectKey, String contentType, long sizeBytes, String sha256, String originalFilename) { }

    public record ExportRow(
            UUID id, UUID clientSubmissionId, String status, String city,
            UUID salespersonId, String salespersonName, UUID storeId, String storeName,
            Long visitOrdinal,
            String customerName, String customerPhone, String visitResult,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, String locationVerificationStatus,
            String locationFailureReason, UUID locationAttemptId, String locationAddress, String locationAdcode,
            String identityMethod, String submittedIpMasked, String userAgentSummary,
            String riskLevel, String riskFlagsJson,
            String storefrontPhotoFilename,
            String wechatScreenshotFilename, String audioFilename, String audioSegmentsJson,
            String transcriptionStatus, String transcript, String summaryStatus, String summaryText,
            Instant createdAt, Instant submittedAt) { }

    public record AdminSubmissionStats(long total, long firstVisitTotal, long revisitTotal,
            long locationAttentionTotal, long reviewPendingTotal, long missingAudioTotal) { }

    /** 固定列映射杜绝客户端排序字符串进入 SQL，列表、统计、CSV 使用同一过滤模型。 */
    public record AdminReadOptions(String locationStatus, String reviewStatus, String mediaStatus,
            String sortBy, String sortDirection) {
        public AdminReadOptions {
            locationStatus = choice(locationStatus, java.util.Set.of("GOOD","LOW_ACCURACY","STALE",
                    "TIME_UNKNOWN","MISSING","USER_REPORTED","OUT_OF_RANGE","STORE_UNLOCATED","LEGACY"), "locationStatus");
            reviewStatus = choice(reviewStatus,java.util.Set.of("PENDING","APPROVED","FOLLOW_UP","FLAGGED"),"reviewStatus");
            mediaStatus = choice(mediaStatus,java.util.Set.of("MISSING_AUDIO","HAS_AUDIO","MISSING_PHOTO"),"mediaStatus");
            sortBy = sortBy == null || sortBy.isBlank() ? "completedAt" : sortBy;
            sortDirection = sortDirection == null || sortDirection.isBlank() ? "desc" : sortDirection;
            choice(sortBy,java.util.Set.of("completedAt","cityName","salespersonName","storeName"),"sortBy");
            choice(sortDirection,java.util.Set.of("asc","desc"),"sortDirection");
        }
        static AdminReadOptions defaults() { return new AdminReadOptions(null,null,null,null,null); }
        private static String choice(String value,java.util.Set<String> choices,String name) {
            if (value == null || value.isBlank()) return null;
            if (!choices.contains(value)) throw TemporaryCheckinException.badRequest(name+"无效");
            return value;
        }
        String orderBy() {
            String column = switch(sortBy) {
                case "cityName" -> "s.city";
                case "salespersonName" -> "s.salesperson_name_snapshot";
                case "storeName" -> "s.store_name_snapshot";
                default -> "s.submitted_at";
            };
            String direction = "asc".equals(sortDirection) ? " ASC" : " DESC";
            return " ORDER BY " + column + " IS NULL ASC, " + column + direction + ", s.id" + direction;
        }
    }

    public record AdminSubmissionRow(
            UUID id, String status, String city, UUID salespersonId, String salespersonName,
            UUID storeId, String storeName, Long visitOrdinal,
            String customerName, String customerPhone, String visitResult,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, String locationVerificationStatus,
            String locationFailureReason, UUID locationAttemptId, String locationAddress, String locationAdcode,
            String identityMethod, String submittedIpMasked, String userAgentSummary,
            String riskLevel, String riskFlagsJson,
            boolean storefrontPhotoAvailable, boolean wechatScreenshotAvailable, boolean audioAvailable,
            MediaReference audio, String audioSegmentsJson, int audioActiveSegmentCount,
            Instant storefrontPhotoDeletedAt, Instant wechatScreenshotDeletedAt, Instant audioDeletedAt,
            String transcriptionStatus, String transcript, String transcriptionErrorCode,
            String summaryStatus, String summaryText, String summaryErrorCode,
            Instant createdAt, Instant submittedAt) { }

    public record MediaStorageStatsRow(
            long activeFiles, long totalBytes, long imageBytes, long audioBytes, Instant oldestCreatedAt) { }

    public record TranscriptionJob(
            UUID submissionId, MediaReference audio, String transcriptionStatus, String asrTaskId,
            String asrRequestId, String transcript, String transcriptionErrorCode, int transcriptionAttempts,
            Instant transcriptionUpdatedAt, String summaryStatus, String summaryText, String summaryErrorCode,
            String summaryModel, Instant summaryUpdatedAt) { }
}
