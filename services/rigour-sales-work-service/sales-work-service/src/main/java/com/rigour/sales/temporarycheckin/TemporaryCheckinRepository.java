package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
                SELECT id, source_record_id, name, city, position, employment_status, status, sort_order
                  FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=? AND status='ACTIVE' AND employment_status<>'离职'
                """ + cityClause + " ORDER BY sort_order, name, id",
                (rs, row) -> salesperson(rs), arguments.toArray());
    }

    public Optional<SalespersonRow> findSalesperson(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, source_record_id, name, city, position, employment_status, status, sort_order
                  FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=? AND id=? AND status='ACTIVE' AND employment_status<>'离职'
                 LIMIT 1
                """, (rs, row) -> salesperson(rs), bin(tenantId), bin(id)).stream().findFirst();
    }

    public List<StoreRow> searchStores(UUID tenantId, String city, String escapedQuery, int limit) {
        return jdbc.query("""
                SELECT id, client_store_id, city, creator_salesperson_id, attribute, name, operating_status,
                       contact_name, contact_phone, area_range, facility_count, business_types_json,
                       intended_businesses_json, cooperation_intent, store_grade, tags_json,
                       longitude, latitude, accuracy_meters, location_captured_at, location_note,
                       status, created_at, updated_at
                  FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND city=? AND status='ACTIVE'
                   AND (name LIKE ? ESCAPE '=' OR contact_name LIKE ? ESCAPE '=')
                 ORDER BY updated_at DESC, name, id
                 LIMIT ?
                """, (rs, row) -> store(rs), bin(tenantId), city,
                "%" + escapedQuery + "%", "%" + escapedQuery + "%", limit);
    }

    public Optional<StoreRow> findStore(UUID tenantId, UUID id) {
        return jdbc.query(storeSelect() + " WHERE tenant_id=? AND id=? AND status='ACTIVE' LIMIT 1",
                (rs, row) -> store(rs), bin(tenantId), bin(id)).stream().findFirst();
    }

    public Optional<StoreRow> findStoreByClientId(UUID tenantId, UUID clientStoreId) {
        return jdbc.query(storeSelect() + " WHERE tenant_id=? AND client_store_id=? LIMIT 1",
                (rs, row) -> store(rs), bin(tenantId), bin(clientStoreId)).stream().findFirst();
    }

    public void insertStore(StoreWrite row) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, city, creator_salesperson_id, attribute, name,
                     operating_status, contact_name, contact_phone, area_range, facility_count,
                     business_types_json, intended_businesses_json, cooperation_intent, store_grade,
                     tags_json, longitude, latitude, accuracy_meters, location_captured_at, location_note,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?,
                        CAST(? AS JSON), ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, bin(row.id()), bin(row.tenantId()), bin(row.clientStoreId()), row.city(),
                bin(row.creatorSalespersonId()), row.attribute(), row.name(), row.operatingStatus(),
                row.contactName(), row.contactPhone(), row.areaRange(), row.facilityCount(),
                row.businessTypesJson(), row.intendedBusinessesJson(), row.cooperationIntent(),
                row.storeGrade(), row.tagsJson(), row.longitude(), row.latitude(), row.accuracyMeters(),
                timestamp(row.locationCapturedAt()), row.locationNote(), timestamp(row.now()), timestamp(row.now()));
    }

    public Optional<SubmissionRow> findSubmissionByClientId(UUID tenantId, UUID clientSubmissionId) {
        return jdbc.query(submissionSelect() + " WHERE tenant_id=? AND client_submission_id=? LIMIT 1",
                (rs, row) -> submission(rs), bin(tenantId), bin(clientSubmissionId)).stream().findFirst();
    }

    public Optional<SubmissionRow> findSubmission(UUID tenantId, UUID id) {
        return jdbc.query(submissionSelect() + " WHERE tenant_id=? AND id=? LIMIT 1",
                (rs, row) -> submission(rs), bin(tenantId), bin(id)).stream().findFirst();
    }

    public void insertSubmission(SubmissionWrite row) {
        GeocodeWrite geocode = row.geocode();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission
                    (id, tenant_id, client_submission_id, submission_key_hash, status, city,
                     salesperson_id, salesperson_name_snapshot, store_id, store_name_snapshot,
                     customer_name, customer_phone, visit_result, longitude, latitude, accuracy_meters,
                     location_captured_at, location_note,
                     location_address, location_formatted_address, location_adcode, location_province,
                     location_city, location_district, location_township, amap_longitude, amap_latitude,
                     geocode_status, geocode_error_code, geocoded_at,
                     privacy_accepted, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, bin(row.id()), bin(row.tenantId()), bin(row.clientSubmissionId()), row.keyHash(), row.city(),
                bin(row.salespersonId()), row.salespersonName(), bin(row.storeId()), row.storeName(),
                row.customerName(), row.customerPhone(), row.visitResult(), row.longitude(), row.latitude(),
                row.accuracyMeters(), timestamp(row.locationCapturedAt()), row.locationNote(),
                geocode.address(), geocode.formattedAddress(), geocode.adcode(), geocode.province(),
                geocode.city(), geocode.district(), geocode.township(), geocode.amapLongitude(),
                geocode.amapLatitude(), geocode.status(), geocode.errorCode(), timestamp(geocode.geocodedAt()),
                timestamp(row.now()), timestamp(row.now()));
    }

    public int updateMedia(UUID tenantId, UUID submissionId, String prefix, MediaWrite media, Instant now) {
        String sql = "UPDATE temp_sales_checkin_submission SET "
                + prefix + "object_key=?, " + prefix + "content_type=?, " + prefix + "size_bytes=?, "
                + prefix + "sha256=?, " + prefix + "original_filename=?, updated_at=? "
                + "WHERE tenant_id=? AND id=? AND status='DRAFT'";
        return jdbc.update(sql, media.objectKey(), media.contentType(), media.sizeBytes(), media.sha256(),
                media.originalFilename(), timestamp(now), bin(tenantId), bin(submissionId));
    }

    public int complete(UUID tenantId, UUID submissionId, Instant submittedAt) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET status='SUBMITTED', submitted_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND status='DRAFT'
                   AND storefront_photo_object_key IS NOT NULL
                """, timestamp(submittedAt), timestamp(submittedAt), bin(tenantId), bin(submissionId));
    }

    public List<ExportRow> export(UUID tenantId, Instant from, Instant toExclusive, String city,
                                  UUID salespersonId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, client_submission_id, status, city, salesperson_id,
                       salesperson_name_snapshot, store_id, store_name_snapshot,
                       customer_name, customer_phone, visit_result,
                       longitude, latitude, accuracy_meters, location_captured_at, location_note,
                       location_address, location_adcode,
                       storefront_photo_original_filename, wechat_screenshot_original_filename,
                       audio_original_filename, created_at, submitted_at
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId)));
        if (from != null) { sql.append(" AND created_at>=?"); arguments.add(timestamp(from)); }
        if (toExclusive != null) { sql.append(" AND created_at<?"); arguments.add(timestamp(toExclusive)); }
        if (city != null) { sql.append(" AND city=?"); arguments.add(city); }
        if (salespersonId != null) { sql.append(" AND salesperson_id=?"); arguments.add(bin(salespersonId)); }
        if (status != null) { sql.append(" AND status=?"); arguments.add(status); }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> exportRow(rs), arguments.toArray());
    }

    public long countAdminSubmissions(UUID tenantId, Instant from, Instant toExclusive, String city,
                                      UUID salespersonId, String status, String escapedQuery) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId)));
        appendAdminFilters(sql, arguments, from, toExclusive, city, salespersonId, status, escapedQuery);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, arguments.toArray());
        return count == null ? 0 : count;
    }

    public List<AdminSubmissionRow> findAdminSubmissions(
            UUID tenantId, Instant from, Instant toExclusive, String city, UUID salespersonId,
            String status, String escapedQuery, int offset, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, status, city, salesperson_id, salesperson_name_snapshot,
                       store_id, store_name_snapshot, customer_name, customer_phone, visit_result,
                       longitude, latitude, accuracy_meters, location_captured_at, location_note,
                       location_address, location_adcode,
                       storefront_photo_object_key, wechat_screenshot_object_key, audio_object_key,
                       created_at, submitted_at
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId)));
        appendAdminFilters(sql, arguments, from, toExclusive, city, salespersonId, status, escapedQuery);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        arguments.add(limit);
        arguments.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> adminSubmission(rs), arguments.toArray());
    }

    public Optional<MediaReference> findMedia(
            UUID tenantId, UUID submissionId, String prefix, String city) {
        String sql = "SELECT " + prefix + "object_key AS object_key, "
                + prefix + "content_type AS content_type, " + prefix + "size_bytes AS size_bytes, "
                + prefix + "sha256 AS sha256, "
                + prefix + "original_filename AS original_filename "
                + "FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=?"
                + (city == null ? "" : " AND city=?") + " LIMIT 1";
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId), bin(submissionId)));
        if (city != null) arguments.add(city);
        return jdbc.query(sql, (rs, row) -> new MediaReference(rs.getString("object_key"),
                rs.getString("content_type"), nullableLong(rs, "size_bytes"),
                rs.getString("sha256"), rs.getString("original_filename")), arguments.toArray()).stream()
                .filter(row -> row.objectKey() != null)
                .findFirst();
    }

    private static void appendAdminFilters(
            StringBuilder sql, List<Object> arguments, Instant from, Instant toExclusive, String city,
            UUID salespersonId, String status, String escapedQuery) {
        if (from != null) { sql.append(" AND created_at>=?"); arguments.add(timestamp(from)); }
        if (toExclusive != null) { sql.append(" AND created_at<?"); arguments.add(timestamp(toExclusive)); }
        if (city != null) { sql.append(" AND city=?"); arguments.add(city); }
        if (salespersonId != null) {
            sql.append(" AND salesperson_id=?");
            arguments.add(bin(salespersonId));
        }
        if (status != null) { sql.append(" AND status=?"); arguments.add(status); }
        if (escapedQuery != null) {
            String pattern = "%" + escapedQuery + "%";
            sql.append("""
                     AND (store_name_snapshot LIKE ? ESCAPE '='
                          OR customer_name LIKE ? ESCAPE '='
                          OR salesperson_name_snapshot LIKE ? ESCAPE '='
                          OR customer_phone LIKE ? ESCAPE '=')
                    """);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
    }

    private static String storeSelect() {
        return """
                SELECT id, client_store_id, city, creator_salesperson_id, attribute, name, operating_status,
                       contact_name, contact_phone, area_range, facility_count, business_types_json,
                       intended_businesses_json, cooperation_intent, store_grade, tags_json,
                       longitude, latitude, accuracy_meters, location_captured_at, location_note,
                       status, created_at, updated_at
                  FROM temp_sales_checkin_store
                """;
    }

    private static String submissionSelect() {
        return """
                SELECT id, client_submission_id, submission_key_hash, status, city, salesperson_id,
                       salesperson_name_snapshot, store_id, store_name_snapshot, customer_name,
                       customer_phone, visit_result, longitude, latitude, accuracy_meters,
                       location_captured_at, location_note, privacy_accepted,
                       storefront_photo_object_key, storefront_photo_content_type,
                       storefront_photo_size_bytes, storefront_photo_sha256,
                       storefront_photo_original_filename,
                       wechat_screenshot_object_key, wechat_screenshot_content_type,
                       wechat_screenshot_size_bytes, wechat_screenshot_sha256,
                       wechat_screenshot_original_filename,
                       audio_object_key, audio_content_type, audio_size_bytes, audio_sha256,
                       audio_original_filename, created_at, submitted_at, updated_at
                  FROM temp_sales_checkin_submission
                """;
    }

    private static SalespersonRow salesperson(ResultSet rs) throws SQLException {
        return new SalespersonRow(uuid(rs, "id"), rs.getString("source_record_id"), rs.getString("name"),
                rs.getString("city"), rs.getString("position"), rs.getString("employment_status"),
                rs.getString("status"), rs.getInt("sort_order"));
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
                rs.getString("location_note"), rs.getString("status"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static SubmissionRow submission(ResultSet rs) throws SQLException {
        return new SubmissionRow(uuid(rs, "id"), uuid(rs, "client_submission_id"),
                rs.getString("submission_key_hash"), rs.getString("status"), rs.getString("city"),
                uuid(rs, "salesperson_id"), rs.getString("salesperson_name_snapshot"),
                uuid(rs, "store_id"), rs.getString("store_name_snapshot"), rs.getString("customer_name"),
                rs.getString("customer_phone"), rs.getString("visit_result"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("accuracy_meters"),
                instant(rs, "location_captured_at"), rs.getString("location_note"),
                rs.getBoolean("privacy_accepted"),
                media(rs, "storefront_photo_"), media(rs, "wechat_screenshot_"), media(rs, "audio_"),
                instant(rs, "created_at"), instant(rs, "submitted_at"), instant(rs, "updated_at"));
    }

    private static MediaReference media(ResultSet rs, String prefix) throws SQLException {
        return new MediaReference(rs.getString(prefix + "object_key"), rs.getString(prefix + "content_type"),
                nullableLong(rs, prefix + "size_bytes"), rs.getString(prefix + "sha256"),
                rs.getString(prefix + "original_filename"));
    }

    private static ExportRow exportRow(ResultSet rs) throws SQLException {
        return new ExportRow(uuid(rs, "id"), uuid(rs, "client_submission_id"), rs.getString("status"),
                rs.getString("city"), uuid(rs, "salesperson_id"), rs.getString("salesperson_name_snapshot"),
                uuid(rs, "store_id"), rs.getString("store_name_snapshot"), rs.getString("customer_name"),
                rs.getString("customer_phone"), rs.getString("visit_result"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("accuracy_meters"),
                instant(rs, "location_captured_at"), rs.getString("location_note"),
                rs.getString("location_address"), rs.getString("location_adcode"),
                rs.getString("storefront_photo_original_filename"),
                rs.getString("wechat_screenshot_original_filename"), rs.getString("audio_original_filename"),
                instant(rs, "created_at"), instant(rs, "submitted_at"));
    }

    private static AdminSubmissionRow adminSubmission(ResultSet rs) throws SQLException {
        return new AdminSubmissionRow(uuid(rs, "id"), rs.getString("status"), rs.getString("city"),
                uuid(rs, "salesperson_id"), rs.getString("salesperson_name_snapshot"),
                uuid(rs, "store_id"), rs.getString("store_name_snapshot"), rs.getString("customer_name"),
                rs.getString("customer_phone"), rs.getString("visit_result"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("accuracy_meters"),
                instant(rs, "location_captured_at"), rs.getString("location_note"),
                rs.getString("location_address"), rs.getString("location_adcode"),
                rs.getString("storefront_photo_object_key") != null,
                rs.getString("wechat_screenshot_object_key") != null,
                rs.getString("audio_object_key") != null,
                instant(rs, "created_at"), instant(rs, "submitted_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
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
            String employmentStatus, String status, int sortOrder) { }

    public record StoreRow(
            UUID id, UUID clientStoreId, String city, UUID creatorSalespersonId, String attribute, String name,
            String operatingStatus, String contactName, String contactPhone, String areaRange, String facilityCount,
            String businessTypesJson, String intendedBusinessesJson, String cooperationIntent, String storeGrade,
            String tagsJson, BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, String status, Instant createdAt, Instant updatedAt) { }

    public record StoreWrite(
            UUID id, UUID tenantId, UUID clientStoreId, String city, UUID creatorSalespersonId, String attribute,
            String name, String operatingStatus, String contactName, String contactPhone, String areaRange,
            String facilityCount, String businessTypesJson, String intendedBusinessesJson, String cooperationIntent,
            String storeGrade, String tagsJson, BigDecimal longitude, BigDecimal latitude,
            BigDecimal accuracyMeters, Instant locationCapturedAt, String locationNote, Instant now) { }

    public record MediaReference(
            String objectKey, String contentType, Long sizeBytes, String sha256, String originalFilename) { }

    public record SubmissionRow(
            UUID id, UUID clientSubmissionId, String keyHash, String status, String city,
            UUID salespersonId, String salespersonName, UUID storeId, String storeName,
            String customerName, String customerPhone, String visitResult,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, boolean privacyAccepted,
            MediaReference storefrontPhoto, MediaReference wechatScreenshot, MediaReference audio,
            Instant createdAt, Instant submittedAt, Instant updatedAt) { }

    public record SubmissionWrite(
            UUID id, UUID tenantId, UUID clientSubmissionId, String keyHash, String city,
            UUID salespersonId, String salespersonName, UUID storeId, String storeName,
            String customerName, String customerPhone, String visitResult,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, GeocodeWrite geocode, Instant now) { }

    public record GeocodeWrite(
            String status, String address, String formattedAddress, String adcode,
            String province, String city, String district, String township,
            BigDecimal amapLongitude, BigDecimal amapLatitude, String errorCode, Instant geocodedAt) { }

    public record MediaWrite(
            String objectKey, String contentType, long sizeBytes, String sha256, String originalFilename) { }

    public record ExportRow(
            UUID id, UUID clientSubmissionId, String status, String city,
            UUID salespersonId, String salespersonName, UUID storeId, String storeName,
            String customerName, String customerPhone, String visitResult,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, String locationAddress, String locationAdcode,
            String storefrontPhotoFilename,
            String wechatScreenshotFilename, String audioFilename, Instant createdAt, Instant submittedAt) { }

    public record AdminSubmissionRow(
            UUID id, String status, String city, UUID salespersonId, String salespersonName,
            UUID storeId, String storeName, String customerName, String customerPhone, String visitResult,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant locationCapturedAt, String locationNote, String locationAddress, String locationAdcode,
            boolean storefrontPhotoAvailable, boolean wechatScreenshotAvailable, boolean audioAvailable,
            Instant createdAt, Instant submittedAt) { }
}
