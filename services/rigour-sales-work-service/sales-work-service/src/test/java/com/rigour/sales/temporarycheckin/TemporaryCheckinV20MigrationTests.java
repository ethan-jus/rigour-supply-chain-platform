package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 用真实 MySQL 验证 V20 将定位例外与历史、已验证定位分开留档。 */
@Testcontainers(disabledWithoutDocker = true)
class TemporaryCheckinV20MigrationTests {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SALESPERSON_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID STORE_WITHOUT_LOCATION_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID STORE_WITH_LOCATION_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID SUBMISSION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-02T09:00:00Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work")
            .withUsername("rigour_sales_work_v20_test")
            .withPassword("rigour_sales_work_v20_test_password");

    @Test
    void preservesLegacyFactsAndEnforcesExplicitUnverifiedLocationState() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("19"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        seedV19Catalog(jdbc);
        insertLegacySubmission(jdbc);

        var migration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("20"))
                .load()
                .migrate();

        assertThat(migration.migrationsExecuted).isEqualTo(1);
        assertLegacyRowsRemainDistinguishable(jdbc);
        assertStoreVerificationContract(jdbc);
        assertSubmissionVerificationContract(jdbc);
    }

    private static void assertLegacyRowsRemainDistinguishable(JdbcTemplate jdbc) {
        Map<String, Object> storeWithoutLocation = jdbc.queryForMap("""
                SELECT location_verification_status, location_failure_reason, location_attempt_id
                  FROM temp_sales_checkin_store
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), STORE_WITHOUT_LOCATION_ID.toString());
        assertThat(storeWithoutLocation.get("location_verification_status")).isEqualTo("LEGACY");
        assertThat(storeWithoutLocation.get("location_failure_reason")).isNull();
        assertThat(storeWithoutLocation.get("location_attempt_id")).isNull();

        Map<String, Object> submission = jdbc.queryForMap("""
                SELECT location_verification_status, location_failure_reason, location_attempt_id
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), SUBMISSION_ID.toString());
        assertThat(submission.get("location_verification_status")).isEqualTo("LEGACY");
        assertThat(submission.get("location_failure_reason")).isNull();
        assertThat(submission.get("location_attempt_id")).isNull();

        Integer nullableSubmissionCoordinateColumns = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.columns
                 WHERE table_schema=DATABASE()
                   AND table_name='temp_sales_checkin_submission'
                   AND column_name IN ('longitude','latitude','accuracy_meters','location_captured_at')
                   AND is_nullable='YES'
                """, Integer.class);
        assertThat(nullableSubmissionCoordinateColumns).isEqualTo(4);
    }

    private static void assertStoreVerificationContract(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_failure_reason='TIMEOUT', location_attempt_id=UUID_TO_BIN(?)
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, STORE_WITHOUT_LOCATION_ID.toString(), TENANT_ID.toString(),
                STORE_WITHOUT_LOCATION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_verification_status='VERIFIED'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), STORE_WITHOUT_LOCATION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_verification_status='UNVERIFIED',
                       location_failure_reason='POSITION_UNAVAILABLE',
                       location_attempt_id=UUID_TO_BIN(?),
                       geocode_status='SKIPPED'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, STORE_WITHOUT_LOCATION_ID.toString(), TENANT_ID.toString(),
                STORE_WITHOUT_LOCATION_ID.toString());
        assertThat(jdbc.queryForObject("""
                SELECT geocode_status
                  FROM temp_sales_checkin_store
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, String.class, TENANT_ID.toString(), STORE_WITHOUT_LOCATION_ID.toString()))
                .isEqualTo("SKIPPED");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_failure_reason=NULL
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), STORE_WITHOUT_LOCATION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_attempt_id=NULL
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), STORE_WITHOUT_LOCATION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_verification_status='VERIFIED'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), STORE_WITH_LOCATION_ID.toString());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_failure_reason='TIMEOUT', location_attempt_id=UUID_TO_BIN(?)
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, STORE_WITH_LOCATION_ID.toString(), TENANT_ID.toString(),
                STORE_WITH_LOCATION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_verification_status='UNVERIFIED',
                       location_failure_reason='TIMEOUT',
                       location_attempt_id=UUID_TO_BIN(?),
                       geocode_status='SKIPPED'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, STORE_WITH_LOCATION_ID.toString(), TENANT_ID.toString(),
                STORE_WITH_LOCATION_ID.toString());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET location_failure_reason='UNKNOWN_FAILURE'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), STORE_WITH_LOCATION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void assertSubmissionVerificationContract(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET location_failure_reason='TIMEOUT', location_attempt_id=UUID_TO_BIN(?)
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, SUBMISSION_ID.toString(), TENANT_ID.toString(), SUBMISSION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET longitude=NULL, latitude=NULL, accuracy_meters=NULL, location_captured_at=NULL
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), SUBMISSION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET location_verification_status='UNVERIFIED',
                       location_failure_reason='TIMEOUT',
                       location_attempt_id=UUID_TO_BIN(?),
                       geocode_status='SKIPPED'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, SUBMISSION_ID.toString(), TENANT_ID.toString(), SUBMISSION_ID.toString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                   AND longitude IS NOT NULL AND latitude IS NOT NULL
                   AND accuracy_meters IS NOT NULL AND location_captured_at IS NOT NULL
                """, Integer.class, TENANT_ID.toString(), SUBMISSION_ID.toString()))
                .isEqualTo(1);

        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET location_verification_status='UNVERIFIED',
                       location_failure_reason='PERMISSION_DENIED',
                       location_attempt_id=UUID_TO_BIN(?),
                       longitude=NULL, latitude=NULL, accuracy_meters=NULL, location_captured_at=NULL,
                       geocode_status='SKIPPED'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, SUBMISSION_ID.toString(), TENANT_ID.toString(), SUBMISSION_ID.toString());
        Map<String, Object> unverified = jdbc.queryForMap("""
                SELECT location_verification_status, location_failure_reason,
                       BIN_TO_UUID(location_attempt_id) AS location_attempt_id, geocode_status
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), SUBMISSION_ID.toString());
        assertThat(unverified.get("location_verification_status")).isEqualTo("UNVERIFIED");
        assertThat(unverified.get("location_failure_reason")).isEqualTo("PERMISSION_DENIED");
        assertThat(unverified.get("location_attempt_id")).isEqualTo(SUBMISSION_ID.toString());
        assertThat(unverified.get("geocode_status")).isEqualTo("SKIPPED");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET longitude=116.3971280
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), SUBMISSION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET location_failure_reason='UNKNOWN_FAILURE'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), SUBMISSION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET location_attempt_id=NULL
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, TENANT_ID.toString(), SUBMISSION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET location_verification_status='VERIFIED',
                       location_failure_reason=NULL,
                       location_attempt_id=NULL,
                       longitude=116.3971280,
                       latitude=39.9165270,
                       accuracy_meters=8.50,
                       location_captured_at=?,
                       geocode_status='RESOLVED'
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, Timestamp.from(NOW), TENANT_ID.toString(), SUBMISSION_ID.toString());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET location_failure_reason='TIMEOUT', location_attempt_id=UUID_TO_BIN(?)
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, SUBMISSION_ID.toString(), TENANT_ID.toString(), SUBMISSION_ID.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void seedV19Catalog(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_salesperson
                    (id, tenant_id, name, city, position, employment_status, status,
                     sort_order, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), '迁移测试销售', '北京', '销售', '在职',
                        'ACTIVE', 0, ?, ?)
                """, SALESPERSON_ID.toString(), TENANT_ID.toString(),
                Timestamp.from(NOW), Timestamp.from(NOW));
        insertStore(jdbc, STORE_WITHOUT_LOCATION_ID, "无定位历史门店", false);
        insertStore(jdbc, STORE_WITH_LOCATION_ID, "有定位历史门店", true);
    }

    private static void insertStore(JdbcTemplate jdbc, UUID id, String name, boolean withLocation) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, city, creator_salesperson_id, attribute, name,
                     operating_status, contact_name, area_range, facility_count, business_types_json,
                     intended_businesses_json, cooperation_intent, tags_json,
                     longitude, latitude, accuracy_meters, location_captured_at,
                     status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), '北京', UUID_TO_BIN(?),
                        '台球', ?, '营业中', '张店长', '100-300平米', '10张球桌',
                        JSON_ARRAY('竞技赛事'), JSON_ARRAY('高德业务'), '高意向', JSON_ARRAY('单店'),
                        ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id.toString(), TENANT_ID.toString(), UUID.randomUUID().toString(),
                SALESPERSON_ID.toString(), name,
                withLocation ? 116.3971280 : null,
                withLocation ? 39.9165270 : null,
                withLocation ? 8.50 : null,
                withLocation ? Timestamp.from(NOW.minusSeconds(30)) : null,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static void insertLegacySubmission(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission
                    (id, tenant_id, client_submission_id, submission_key_hash, status, city,
                     salesperson_id, salesperson_name_snapshot, store_id, store_name_snapshot,
                     customer_name, visit_result, longitude, latitude, accuracy_meters,
                     location_captured_at, privacy_accepted, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'DRAFT', '北京',
                        UUID_TO_BIN(?), '迁移测试销售', UUID_TO_BIN(?), '有定位历史门店',
                        '李经理', '迁移定位例外', 116.3971280, 39.9165270, 8.50, ?, 1, ?, ?)
                """, SUBMISSION_ID.toString(), TENANT_ID.toString(), UUID.randomUUID().toString(),
                "a".repeat(64), SALESPERSON_ID.toString(), STORE_WITH_LOCATION_ID.toString(),
                Timestamp.from(NOW.minusSeconds(10)), Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
