package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.GeocodeWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.MediaWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.StoreWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.SubmissionWrite;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 用真实 MySQL 约束验证临时打卡三表的租户隔离、幂等和状态边界。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TemporaryCheckinPersistenceIntegrationTests {

    private static final UUID CONFIGURED_TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");

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
        registry.add("rigour.sales.temporary-checkin.enabled", () -> true);
        registry.add("rigour.sales.temporary-checkin.identity-enforcement-enabled", () -> false);
        registry.add("rigour.sales.temporary-checkin.tenant-id", CONFIGURED_TENANT_ID::toString);
    }

    @Autowired
    private TemporaryCheckinRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void enforcesTenantScopedNaturalKeysWithoutBindingVisitSalespersonToStoreCreator() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID salespersonA = UUID.randomUUID();
        UUID salespersonA2 = UUID.randomUUID();
        UUID salespersonB = UUID.randomUUID();
        insertSalesperson(tenantA, salespersonA, "销售甲", "北京");
        insertSalesperson(tenantA, salespersonA2, "销售乙", "北京");
        insertSalesperson(tenantB, salespersonB, "销售甲", "北京");

        assertThatThrownBy(() -> insertSalesperson(tenantA, UUID.randomUUID(), "销售甲", "北京"))
                .isInstanceOf(DataIntegrityViolationException.class);
        setSalespersonSourceRecord(tenantA, salespersonA, "rec-salesperson-1");
        assertThatThrownBy(() -> setSalespersonSourceRecord(tenantA, salespersonA2, "rec-salesperson-1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID sharedClientStoreId = UUID.randomUUID();
        UUID storeA = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();
        repository.insertStore(store(storeA, tenantA, sharedClientStoreId, salespersonA, "北京一店"));
        repository.insertStore(store(storeB, tenantB, sharedClientStoreId, salespersonB, "北京二店"));

        assertThatThrownBy(() -> repository.insertStore(
                store(UUID.randomUUID(), tenantA, sharedClientStoreId, salespersonA, "重复门店")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.insertStore(
                store(UUID.randomUUID(), tenantA, UUID.randomUUID(), salespersonB, "跨租户销售门店")))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID firstSourceStore = UUID.randomUUID();
        UUID secondSourceStore = UUID.randomUUID();
        repository.insertStore(store(firstSourceStore, tenantA, UUID.randomUUID(), salespersonA, "来源门店一"));
        repository.insertStore(store(secondSourceStore, tenantA, UUID.randomUUID(), salespersonA, "来源门店二"));
        setSourceRecord(tenantA, firstSourceStore, "rec-feishu-1");
        assertThatThrownBy(() -> setSourceRecord(tenantA, secondSourceStore, "rec-feishu-1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID independentlyAssignedSubmissionId = UUID.randomUUID();
        repository.insertSubmission(submission(
                independentlyAssignedSubmissionId, tenantA, UUID.randomUUID(), salespersonA2, storeA));
        assertThat(repository.findSubmission(tenantA, independentlyAssignedSubmissionId).orElseThrow()
                .salespersonId()).isEqualTo(salespersonA2);

        UUID historicalStoreId = UUID.randomUUID();
        insertHistoricalStoreWithoutCreatorOrCoordinates(tenantA, historicalStoreId);
        assertThat(jdbc.queryForObject("""
                SELECT facility_count FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND id=?
                """, String.class, bin(tenantA), bin(historicalStoreId))).isEqualTo("10张球桌");
        var historicalStore = repository.findStore(tenantA, historicalStoreId).orElseThrow();
        assertThat(historicalStore.creatorSalespersonId()).isNull();
        assertThat(historicalStore.longitude()).isNull();
        assertThat(historicalStore.locationNote()).isEqualTo("飞书存量地理位置文本");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_store SET longitude=116.3971280
                 WHERE tenant_id=? AND id=?
                """, bin(tenantA), bin(historicalStoreId)))
                .isInstanceOf(DataAccessException.class);

        UUID sharedClientSubmissionId = UUID.randomUUID();
        repository.insertSubmission(submission(
                UUID.randomUUID(), tenantA, sharedClientSubmissionId, salespersonA, storeA));
        repository.insertSubmission(submission(
                UUID.randomUUID(), tenantB, sharedClientSubmissionId, salespersonB, storeB));
        assertThatThrownBy(() -> repository.insertSubmission(submission(
                UUID.randomUUID(), tenantA, sharedClientSubmissionId, salespersonA, storeA)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void keepsMediaMetadataAtomicAndCompletesWithOnlyRequiredStorefrontPhoto() {
        UUID tenantId = UUID.randomUUID();
        UUID salespersonId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        insertSalesperson(tenantId, salespersonId, "完成流程销售", "深圳");
        repository.insertStore(store(storeId, tenantId, UUID.randomUUID(), salespersonId, "完成流程门店"));
        repository.insertSubmission(submission(
                submissionId, tenantId, UUID.randomUUID(), salespersonId, storeId));

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET storefront_photo_object_key='tenant/partial.jpg'
                 WHERE tenant_id=? AND id=?
                """, bin(tenantId), bin(submissionId)))
                .isInstanceOf(DataAccessException.class);

        assertThat(repository.updateMedia(tenantId, submissionId, "storefront_photo_",
                media("photo.jpg", "image/jpeg", HASH_A), NOW.plusSeconds(1))).isEqualTo(1);
        assertThat(repository.complete(tenantId, submissionId, NOW.plusSeconds(2))).isEqualTo(1);
        var completed = repository.findSubmission(tenantId, submissionId).orElseThrow();
        assertThat(completed.status()).isEqualTo("SUBMITTED");
        assertThat(completed.submittedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(completed.wechatScreenshot().objectKey()).isNull();
        assertThat(completed.audio().objectKey()).isNull();

        assertThat(repository.updateMedia(tenantId, submissionId, "audio_",
                media("late.m4a", "audio/mp4", HASH_C), NOW.plusSeconds(3))).isZero();
        assertThat(repository.complete(tenantId, submissionId, NOW.plusSeconds(4))).isZero();
        assertThat(repository.findSubmission(tenantId, submissionId).orElseThrow().audio().objectKey()).isNull();
    }

    @Test
    void persistsReadableAmapAddressAlongsideOriginalGpsCoordinates() {
        UUID tenantId = UUID.randomUUID();
        UUID salespersonId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        insertSalesperson(tenantId, salespersonId, "地址测试销售", "北京");
        repository.insertStore(store(storeId, tenantId, UUID.randomUUID(), salespersonId, "地址测试门店"));
        SubmissionWrite write = new SubmissionWrite(submissionId, tenantId, UUID.randomUUID(), HASH_A,
                "北京", salespersonId, "地址测试销售", storeId, "地址测试门店", "王经理",
                "13900000000", "已完成沟通", new BigDecimal("116.4251234"),
                new BigDecimal("39.8867886"), new BigDecimal("8.20"), NOW.minusSeconds(5), "门店入口",
                new GeocodeWrite("RESOLVED",
                        "北京市东城区夕照寺街16号；东城区龙潭路与夕照寺街交叉口东南60米",
                        "北京市东城区夕照寺街16号", "110101", "北京市", null, "东城区",
                        "龙潭街道", new BigDecimal("116.431200"), new BigDecimal("39.888300"),
                        null, NOW), TemporaryCheckinService.PRIVACY_NOTICE_VERSION, NOW);

        repository.insertSubmission(write);

        var stored = jdbc.queryForMap("""
                SELECT longitude, latitude, location_address, location_formatted_address,
                       location_adcode, location_district, amap_longitude, amap_latitude,
                       geocode_status, geocode_error_code
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=?
                """, bin(tenantId), bin(submissionId));
        assertThat(stored.get("location_address")).isEqualTo(
                "北京市东城区夕照寺街16号；东城区龙潭路与夕照寺街交叉口东南60米");
        assertThat(stored.get("location_adcode")).isEqualTo("110101");
        assertThat(stored.get("location_district")).isEqualTo("东城区");
        assertThat(stored.get("geocode_status")).isEqualTo("RESOLVED");
        assertThat(stored.get("geocode_error_code")).isNull();
        assertThat((BigDecimal) stored.get("longitude")).isEqualByComparingTo("116.4251234");
        assertThat((BigDecimal) stored.get("amap_longitude")).isEqualByComparingTo("116.431200");
    }

    private void insertSalesperson(UUID tenantId, UUID id, String name, String city) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_salesperson
                    (id, tenant_id, name, city, employment_status, status, sort_order,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, '在职', 'ACTIVE', 0, ?, ?)
                """, bin(id), bin(tenantId), name, city, timestamp(NOW), timestamp(NOW));
    }

    private void setSalespersonSourceRecord(UUID tenantId, UUID salespersonId, String sourceRecordId) {
        jdbc.update("""
                UPDATE temp_sales_checkin_salesperson SET source_record_id=?
                 WHERE tenant_id=? AND id=?
                """, sourceRecordId, bin(tenantId), bin(salespersonId));
    }

    private void setSourceRecord(UUID tenantId, UUID storeId, String sourceRecordId) {
        jdbc.update("""
                UPDATE temp_sales_checkin_store SET source_record_id=?
                 WHERE tenant_id=? AND id=?
                """, sourceRecordId, bin(tenantId), bin(storeId));
    }

    private void insertHistoricalStoreWithoutCreatorOrCoordinates(UUID tenantId, UUID storeId) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, source_record_id, city,
                     creator_salesperson_id, attribute, name, operating_status, contact_name,
                     contact_phone, area_range, facility_count, business_types_json,
                     intended_businesses_json, cooperation_intent, store_grade, tags_json,
                     longitude, latitude, accuracy_meters, location_captured_at, location_note,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, 'rec-historical-store', '北京', NULL, '台球', '历史门店', '营业中',
                        '历史联系人', NULL, '100-300平米', '10张球桌', JSON_ARRAY('历史业态'),
                        JSON_ARRAY('历史意向'), '中意向', NULL, JSON_ARRAY('历史标签'),
                        NULL, NULL, NULL, NULL, '飞书存量地理位置文本', 'ACTIVE', ?, ?)
                """, bin(storeId), bin(tenantId), bin(UUID.randomUUID()), timestamp(NOW), timestamp(NOW));
    }

    private static StoreWrite store(
            UUID id, UUID tenantId, UUID clientStoreId, UUID salespersonId, String name) {
        return new StoreWrite(id, tenantId, clientStoreId, "北京", salespersonId, "台球", name,
                "营业中", "张店长", "13800000000", "100-300平米", "8张球桌",
                "[\"竞技赛事\"]", "[\"高德业务\"]", "高意向", "A类", "[\"连锁\"]",
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270"), new BigDecimal("12.50"),
                NOW.minusSeconds(30), "店门口", null, null, null, null, null,
                new GeocodeWrite("KEY_MISSING", null, null, null, null, null, null, null,
                        null, null, "AMAP_WEB_KEY_MISSING", NOW), NOW);
    }

    private static SubmissionWrite submission(
            UUID id, UUID tenantId, UUID clientSubmissionId, UUID salespersonId, UUID storeId) {
        return new SubmissionWrite(id, tenantId, clientSubmissionId, HASH_A, "北京", salespersonId,
                "销售快照", storeId, "门店快照", "李经理", "13900000000", "已完成沟通",
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270"), new BigDecimal("9.80"),
                NOW.minusSeconds(10), "门店内",
                new GeocodeWrite("KEY_MISSING", null, null, null, null, null, null, null,
                        null, null, "AMAP_WEB_KEY_MISSING", NOW),
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION, NOW);
    }

    private static MediaWrite media(String filename, String contentType, String hash) {
        return new MediaWrite(CONFIGURED_TENANT_ID + "/temporary-checkin/" + filename,
                contentType, 128, hash, filename);
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
