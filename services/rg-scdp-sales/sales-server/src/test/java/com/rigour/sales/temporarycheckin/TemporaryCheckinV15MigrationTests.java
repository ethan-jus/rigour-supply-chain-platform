package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
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

/** 用真实 MySQL 从 V14 演进到 V15，验证姓名清洗、来源身份保留与新索引。 */
@Testcontainers(disabledWithoutDocker = true)
class TemporaryCheckinV15MigrationTests {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SALESPERSON_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID STORE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID SUBMISSION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work")
            .withUsername("rigour_sales_work_v15_test")
            .withPassword("rigour_sales_work_v15_test_password");

    @Test
    void cleansDatePrefixesWithoutChangingSourceIdsOrForeignKeysAndAddsVisitIndexes() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("14"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        seedV14Data(jdbc);

        var migration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("15"))
                .load()
                .migrate();

        assertThat(migration.migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT name FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, String.class, TENANT_ID.toString(), SALESPERSON_ID.toString())).isEqualTo("郭建华");
        assertThat(jdbc.queryForObject("""
                SELECT source_record_id FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, String.class, TENANT_ID.toString(), SALESPERSON_ID.toString()))
                .isEqualTo("feishu-sales-csv:stable-source");
        assertThat(jdbc.queryForObject("""
                SELECT salesperson_name_snapshot FROM temp_sales_checkin_submission
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, String.class, TENANT_ID.toString(), SUBMISSION_ID.toString())).isEqualTo("郭建华");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                   AND salesperson_id=UUID_TO_BIN(?) AND store_id=UUID_TO_BIN(?)
                """, Integer.class, TENANT_ID.toString(), SUBMISSION_ID.toString(),
                SALESPERSON_ID.toString(), STORE_ID.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema=DATABASE()
                   AND table_name='temp_sales_checkin_submission'
                   AND index_name='idx_temp_sales_checkin_submission_visit_rank'
                   AND column_name IN (
                       'tenant_id','status','salesperson_id','store_id','submitted_at','id'
                   )
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE constraint_schema=DATABASE()
                   AND table_name='temp_sales_checkin_store'
                   AND constraint_name='uk_temp_sales_checkin_store_source_poi'
                   AND constraint_type='UNIQUE'
                """, Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> insertDuplicatePoiStore(jdbc))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("15"))
                .load()
                .migrate().migrationsExecuted).isZero();
    }

    private static void seedV14Data(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_salesperson
                    (id, tenant_id, source_record_id, name, city, position, employment_status,
                     status, sort_order, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'feishu-sales-csv:stable-source',
                        '2026-08-19-郭建华', '北京', '销售', '在职', 'ACTIVE', 1, ?, ?)
                """, SALESPERSON_ID.toString(), TENANT_ID.toString(),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, source_poi_id, city, creator_salesperson_id,
                     attribute, name, operating_status, contact_name, area_range, facility_count,
                     business_types_json, intended_businesses_json, cooperation_intent, tags_json,
                     longitude, latitude, accuracy_meters, location_captured_at, status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 'B0FF-STABLE-POI', '北京',
                        UUID_TO_BIN(?), '台球', '测试门店', '营业中', '张店长', '100-300平米',
                        '10张球桌', JSON_ARRAY('竞技赛事'), JSON_ARRAY('高德业务'), '高意向',
                        JSON_ARRAY('单店'), 116.3971280, 39.9165270, 8.50, ?, 'ACTIVE', ?, ?)
                """, STORE_ID.toString(), TENANT_ID.toString(), UUID.randomUUID().toString(),
                SALESPERSON_ID.toString(), Timestamp.from(NOW.minusSeconds(30)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission
                    (id, tenant_id, client_submission_id, submission_key_hash, status, city,
                     salesperson_id, salesperson_name_snapshot, store_id, store_name_snapshot,
                     customer_name, visit_result, longitude, latitude, accuracy_meters,
                     location_captured_at, privacy_accepted, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'DRAFT', '北京',
                        UUID_TO_BIN(?), '2026-08-19-郭建华', UUID_TO_BIN(?), '测试门店',
                        '李经理', '测试拜访', 116.3971280, 39.9165270, 8.50, ?, 1, ?, ?)
                """, SUBMISSION_ID.toString(), TENANT_ID.toString(), UUID.randomUUID().toString(),
                "a".repeat(64), SALESPERSON_ID.toString(), STORE_ID.toString(),
                Timestamp.from(NOW.minusSeconds(10)), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static void insertDuplicatePoiStore(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, source_poi_id, city, creator_salesperson_id,
                     attribute, name, operating_status, contact_name, area_range, facility_count,
                     business_types_json, intended_businesses_json, cooperation_intent, tags_json,
                     longitude, latitude, accuracy_meters, location_captured_at, status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 'B0FF-STABLE-POI', '北京',
                        UUID_TO_BIN(?), '台球', '重复门店', '营业中', '李店长', '100-300平米',
                        '8张球桌', JSON_ARRAY('竞技赛事'), JSON_ARRAY('高德业务'), '中意向',
                        JSON_ARRAY('单店'), 116.3971280, 39.9165270, 8.50, ?, 'ACTIVE', ?, ?)
                """, UUID.randomUUID().toString(), TENANT_ID.toString(), UUID.randomUUID().toString(),
                SALESPERSON_ID.toString(), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
