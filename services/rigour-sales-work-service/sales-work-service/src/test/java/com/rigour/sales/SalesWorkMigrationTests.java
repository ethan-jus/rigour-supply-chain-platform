package com.rigour.sales;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

    /** 使用一次性MySQL 8.4验证Sales Work迁移，不连接共享DEV。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SalesWorkMigrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work")
            .withUsername("rigour_sales_work_test")
            .withPassword("rigour_sales_work_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migratesCompleteSalesWorkSchema() {
        Integer migrationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class);
        Integer tableCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema=DATABASE()
                   AND (table_name LIKE 'sales\\_%' OR table_name LIKE 'crm\\_%'
                        OR table_name LIKE 'temp\\_sales\\_checkin\\_%')
                """, Integer.class);
        Integer editableStoreTableCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_name='sales_store'
                """, Integer.class);
        Integer storefrontEvidenceColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='sales_visit_evidence'
                   AND column_name IN ('client_evidence_id','evidence_role','capture_source','captured_at',
                                       'media_type','object_size_bytes','longitude','latitude',
                                       'accuracy_meters','distance_to_target_meters')
                """, Integer.class);
        Integer visitPlanExecutionConstraintCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE constraint_schema=DATABASE()
                   AND constraint_name IN ('uk_sales_visit_plan_execution','ck_sales_visit_plan_status',
                                           'uk_sales_visit_plan_active_store')
                """, Integer.class);
        Integer activePlanGeneratedColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='sales_visit_plan'
                   AND column_name='active_store_id' AND extra LIKE '%STORED GENERATED%'
                """, Integer.class);
        Integer decodedContentHashIndexColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='sales_recording_clip'
                   AND index_name='idx_sales_recording_clip_decoded_content_hash'
                   AND column_name IN ('tenant_id', 'perceptual_hash')
                """, Integer.class);
        Integer temporaryTableCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema=DATABASE()
                   AND table_name IN ('temp_sales_checkin_salesperson',
                                      'temp_sales_checkin_store',
                                      'temp_sales_checkin_submission')
                """, Integer.class);
        Integer temporaryTenantColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE()
                   AND table_name IN ('temp_sales_checkin_salesperson',
                                      'temp_sales_checkin_store',
                                      'temp_sales_checkin_submission')
                   AND column_name='tenant_id' AND is_nullable='NO'
                """, Integer.class);
        Integer temporaryMediaColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='temp_sales_checkin_submission'
                   AND column_name IN (
                       'storefront_photo_object_key','storefront_photo_content_type',
                       'storefront_photo_size_bytes','storefront_photo_sha256',
                       'storefront_photo_original_filename','wechat_screenshot_object_key',
                       'wechat_screenshot_content_type','wechat_screenshot_size_bytes',
                       'wechat_screenshot_sha256','wechat_screenshot_original_filename',
                       'audio_object_key','audio_content_type','audio_size_bytes','audio_sha256',
                       'audio_original_filename'
                   )
                """, Integer.class);
        Integer temporaryReadableLocationColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='temp_sales_checkin_submission'
                   AND column_name IN (
                       'location_address','location_formatted_address','location_adcode',
                       'location_province','location_city','location_district','location_township',
                       'amap_longitude','amap_latitude','geocode_status','geocode_error_code','geocoded_at'
                   )
                """, Integer.class);
        Integer temporarySalespersonImportColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='temp_sales_checkin_salesperson'
                   AND (
                       (column_name='source_record_id' AND data_type='varchar'
                            AND character_maximum_length=128 AND is_nullable='YES')
                       OR (column_name='position' AND data_type='varchar'
                            AND character_maximum_length=64 AND is_nullable='YES')
                       OR (column_name='employment_status' AND data_type='varchar'
                            AND character_maximum_length=24 AND is_nullable='NO')
                   )
                """, Integer.class);
        Integer temporaryStoreImportColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='temp_sales_checkin_store'
                   AND (
                       (column_name='creator_salesperson_id' AND data_type='binary'
                            AND character_maximum_length=16 AND is_nullable='YES')
                       OR (column_name='facility_count' AND data_type='varchar'
                            AND character_maximum_length=128 AND is_nullable='NO')
                       OR (column_name IN ('longitude','latitude','accuracy_meters')
                            AND data_type='decimal' AND is_nullable='YES')
                       OR (column_name='location_captured_at' AND data_type='datetime'
                            AND is_nullable='YES')
                   )
                """, Integer.class);
        Integer temporaryUniqueConstraintCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE constraint_schema=DATABASE() AND constraint_type='UNIQUE'
                   AND constraint_name IN (
                       'uk_temp_sales_checkin_salesperson_tenant_id',
                       'uk_temp_sales_checkin_salesperson_source_record',
                       'uk_temp_sales_checkin_salesperson_name_city',
                       'uk_temp_sales_checkin_store_tenant_id',
                       'uk_temp_sales_checkin_store_client_id',
                       'uk_temp_sales_checkin_store_source_record',
                       'uk_temp_sales_checkin_store_source_poi',
                       'uk_temp_sales_checkin_submission_tenant_id',
                       'uk_temp_sales_checkin_submission_client_id'
                   )
                """, Integer.class);
        Integer temporaryForeignKeyCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY'
                   AND constraint_name IN (
                       'fk_temp_sales_checkin_store_creator',
                       'fk_temp_sales_checkin_submission_salesperson',
                       'fk_temp_sales_checkin_submission_store'
                   )
                """, Integer.class);
        Integer temporarySubmissionStoreForeignKeyColumnCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                 WHERE constraint_schema=DATABASE()
                   AND table_name='temp_sales_checkin_submission'
                   AND constraint_name='fk_temp_sales_checkin_submission_store'
                   AND column_name IN ('tenant_id','store_id')
                   AND referenced_table_name='temp_sales_checkin_store'
                """, Integer.class);
        Integer temporaryCheckConstraintCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE constraint_schema=DATABASE() AND constraint_type='CHECK'
                   AND constraint_name LIKE 'ck\\_temp\\_sales\\_checkin\\_%'
                """, Integer.class);

        assertThat(migrationCount).isEqualTo(15);
        assertThat(tableCount).isEqualTo(35);
        assertThat(editableStoreTableCount).isZero();
        assertThat(storefrontEvidenceColumnCount).isEqualTo(10);
        assertThat(visitPlanExecutionConstraintCount).isEqualTo(3);
        assertThat(activePlanGeneratedColumnCount).isEqualTo(1);
        assertThat(decodedContentHashIndexColumnCount).isEqualTo(2);
        assertThat(temporaryTableCount).isEqualTo(3);
        assertThat(temporaryTenantColumnCount).isEqualTo(3);
        assertThat(temporaryMediaColumnCount).isEqualTo(15);
        assertThat(temporaryReadableLocationColumnCount).isEqualTo(12);
        assertThat(temporarySalespersonImportColumnCount).isEqualTo(3);
        assertThat(temporaryStoreImportColumnCount).isEqualTo(6);
        assertThat(temporaryUniqueConstraintCount).isEqualTo(9);
        assertThat(temporaryForeignKeyCount).isEqualTo(3);
        assertThat(temporarySubmissionStoreForeignKeyColumnCount).isEqualTo(2);
        assertThat(temporaryCheckConstraintCount).isEqualTo(20);
    }
}
