package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 用真实 MySQL 验证 V18 单录音数据向 V19 多段清单的可审计回填。 */
@Testcontainers(disabledWithoutDocker = true)
class TemporaryCheckinV19MigrationTests {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SALESPERSON_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID STORE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACTIVE_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID DELETED_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID EMPTY_ID = UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work")
            .withUsername("rigour_sales_work_v19_test")
            .withPassword("rigour_sales_work_v19_test_password");

    @Test
    void backfillsActiveDeletedAndEmptyLegacyAudioWithoutLosingAuditFacts() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("18"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        seedV18Catalog(jdbc);
        insertSubmission(jdbc, ACTIVE_ID, "tenant/active.m4a", 123L, null, null, null);
        insertSubmission(jdbc, DELETED_ID, "tenant/deleted.m4a", 456L,
                NOW.minusSeconds(60), "admin", "测试文件清理");
        insertSubmission(jdbc, EMPTY_ID, null, null, null, null, null);

        var migration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("19"))
                .load()
                .migrate();

        assertThat(migration.migrationsExecuted).isEqualTo(1);
        Map<String, Object> active = audioState(jdbc, ACTIVE_ID);
        assertThat(((Number) active.get("segment_count")).intValue()).isEqualTo(1);
        assertThat(active.get("segment_id")).isEqualTo(ACTIVE_ID.toString());
        assertThat(active.get("object_key")).isEqualTo("tenant/active.m4a");
        assertThat(active.get("deleted_at")).isNull();
        assertThat(((Number) active.get("audio_active_segment_count")).intValue()).isEqualTo(1);
        assertThat(((Number) active.get("audio_active_size_bytes")).longValue()).isEqualTo(123L);

        Map<String, Object> deleted = audioState(jdbc, DELETED_ID);
        assertThat(((Number) deleted.get("segment_count")).intValue()).isEqualTo(1);
        assertThat(deleted.get("segment_id")).isEqualTo(DELETED_ID.toString());
        assertThat(deleted.get("deleted_at")).isNotNull();
        assertThat(deleted.get("deleted_by")).isEqualTo("admin");
        assertThat(deleted.get("deletion_reason")).isEqualTo("测试文件清理");
        assertThat(((Number) deleted.get("audio_active_segment_count")).intValue()).isZero();
        assertThat(((Number) deleted.get("audio_active_size_bytes")).longValue()).isZero();

        Map<String, Object> empty = audioState(jdbc, EMPTY_ID);
        assertThat(((Number) empty.get("segment_count")).intValue()).isZero();
        assertThat(((Number) empty.get("audio_active_segment_count")).intValue()).isZero();
        assertThat(((Number) empty.get("audio_active_size_bytes")).longValue()).isZero();
    }

    private static Map<String, Object> audioState(JdbcTemplate jdbc, UUID submissionId) {
        return jdbc.queryForMap("""
                SELECT JSON_LENGTH(audio_segments_json) AS segment_count,
                       JSON_UNQUOTE(JSON_EXTRACT(audio_segments_json, '$[0].segmentId')) AS segment_id,
                       JSON_UNQUOTE(JSON_EXTRACT(audio_segments_json, '$[0].objectKey')) AS object_key,
                       NULLIF(JSON_UNQUOTE(JSON_EXTRACT(audio_segments_json, '$[0].deletedAt')), 'null')
                           AS deleted_at,
                       NULLIF(JSON_UNQUOTE(JSON_EXTRACT(audio_segments_json, '$[0].deletedBy')), 'null')
                           AS deleted_by,
                       NULLIF(JSON_UNQUOTE(JSON_EXTRACT(audio_segments_json, '$[0].deletionReason')), 'null')
                           AS deletion_reason,
                       audio_active_segment_count, audio_active_size_bytes
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), submissionId.toString());
    }

    private static void seedV18Catalog(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_salesperson
                    (id, tenant_id, name, city, position, employment_status, status,
                     sort_order, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), '迁移测试销售', '北京', '销售', '在职',
                        'ACTIVE', 0, ?, ?)
                """, SALESPERSON_ID.toString(), TENANT_ID.toString(),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, city, creator_salesperson_id, attribute, name,
                     operating_status, contact_name, area_range, facility_count, business_types_json,
                     intended_businesses_json, cooperation_intent, tags_json, status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), '北京', UUID_TO_BIN(?),
                        '台球', '迁移测试门店', '营业中', '张店长', '100-300平米', '10张球桌',
                        JSON_ARRAY('竞技赛事'), JSON_ARRAY('高德业务'), '高意向',
                        JSON_ARRAY('单店'), 'ACTIVE', ?, ?)
                """, STORE_ID.toString(), TENANT_ID.toString(), UUID.randomUUID().toString(),
                SALESPERSON_ID.toString(), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static void insertSubmission(
            JdbcTemplate jdbc, UUID id, String objectKey, Long sizeBytes, Instant deletedAt,
            String deletedBy, String deletionReason) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission
                    (id, tenant_id, client_submission_id, submission_key_hash, status, city,
                     salesperson_id, salesperson_name_snapshot, store_id, store_name_snapshot,
                     customer_name, visit_result, longitude, latitude, accuracy_meters,
                     location_captured_at, privacy_accepted,
                     audio_object_key, audio_content_type, audio_size_bytes, audio_sha256,
                     audio_original_filename, audio_deleted_at, audio_deleted_by,
                     audio_deletion_reason, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'DRAFT', '北京',
                        UUID_TO_BIN(?), '迁移测试销售', UUID_TO_BIN(?), '迁移测试门店',
                        '李经理', '迁移录音', 116.3971280, 39.9165270, 8.50, ?, 1,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), TENANT_ID.toString(), UUID.randomUUID().toString(), "a".repeat(64),
                SALESPERSON_ID.toString(), STORE_ID.toString(), Timestamp.from(NOW.minusSeconds(30)),
                objectKey, objectKey == null ? null : "audio/mp4", sizeBytes,
                objectKey == null ? null : "b".repeat(64), objectKey == null ? null : "visit.m4a",
                deletedAt == null ? null : Timestamp.from(deletedAt), deletedBy, deletionReason,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
