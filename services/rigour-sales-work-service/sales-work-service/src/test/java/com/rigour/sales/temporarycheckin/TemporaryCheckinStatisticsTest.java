package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.AdminReadOptions;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL 迁移表上验证全量筛选、日界、分组分页和既有后台权限，全部数据为测试生成。 */
@Testcontainers(disabledWithoutDocker = true)
class TemporaryCheckinStatisticsTest {
    @Container static final org.testcontainers.mysql.MySQLContainer MYSQL = new org.testcontainers.mysql.MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work").withUsername("statistics_test").withPassword("statistics_test_password");
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SALES = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_SALES = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID STORE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID STORE_TWO = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_STORE = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-08T02:00:00Z");
    private static final AdminScope GLOBAL = new AdminScope(UUID.randomUUID(), "statistics-admin", null);
    private JdbcTemplate jdbc;
    private TemporaryCheckinRepository originals;
    private TemporaryCheckinService checkins;
    private TemporaryCheckinStatisticsService statistics;
    private MockMvc mvc;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @BeforeEach void prepare() {
        connect("UTC");
        jdbc.update("DELETE FROM temp_sales_checkin_submission");
        jdbc.update("DELETE FROM temp_sales_checkin_store");
        jdbc.update("DELETE FROM temp_sales_checkin_salesperson");
        salesperson(TENANT, SALES, "测试销售");
        salesperson(OTHER, OTHER_SALES, "另租户销售");
        store(TENANT, STORE); store(TENANT, STORE_TWO); store(OTHER, OTHER_STORE);
    }

    private void connect(String zone) {
        String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=" + zone + "&forceConnectionTimeZoneToSession=true";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword()));
        originals = new TemporaryCheckinRepository(jdbc);
        var properties = new TemporaryCheckinProperties(); properties.setTenantId(TENANT.toString());
        var catalog = mock(TemporaryCheckinAdminAuthRepository.class);
        when(catalog.listActiveCities(TENANT)).thenReturn(List.of("北京", "深圳"));
        var beans = new DefaultListableBeanFactory();
        // 只调用真实筛选校验；媒体、定位和写入协作者不参与统计路径。
        checkins = new TemporaryCheckinService(originals, null, null, null, catalog, properties,
                null, null, null, null, null, null, null, null, null, Clock.systemUTC(),
                beans.getBeanProvider(TemporaryCheckinAiClient.class),
                beans.getBeanProvider(org.springframework.boot.servlet.autoconfigure.MultipartProperties.class));
        statistics = new TemporaryCheckinStatisticsService(checkins, new TemporaryCheckinStatisticsRepository(jdbc), properties);
        mvc = MockMvcBuilders.standaloneSetup(new TemporaryCheckinStatisticsController(statistics, new TemporaryCheckinAdminAccessPolicy()))
                .setControllerAdvice(new TemporaryCheckinExceptionHandler()).build();
    }

    @Test void aggregatesAllRecordsBeforePagingAndExportsEveryGroup() {
        for (int index = 0; index < 55; index++) submission(TENANT, SALES, STORE, "北京", "测试销售", "门店", "SUBMITTED", NOW.minus(index, ChronoUnit.DAYS));
        submission(TENANT, SALES, STORE, "北京", "测试销售", "门店", "SUBMITTED", NOW.plusSeconds(3600));
        submission(TENANT, SALES, STORE_TWO, "北京", "测试销售", "二店", "SUBMITTED", NOW.plusSeconds(7200));
        submission(TENANT, SALES, STORE, "北京", "测试销售", "草稿", "DRAFT", NOW);
        var first = statistics.summary(GLOBAL, null, null, null, null, null, null, null, AdminReadOptions.defaults(), 0, 50);
        var second = statistics.summary(GLOBAL, null, null, null, null, null, null, null, AdminReadOptions.defaults(), 1, 50);
        assertThat(first.totalVisits()).isEqualTo(58);
        assertThat(first.pendingReviewTotal()).isEqualTo(58);
        assertThat(first.checkedInSalespeople()).isEqualTo(1);
        assertThat(first.totalElements()).isEqualTo(55);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items()).hasSize(50);
        assertThat(second.items()).hasSize(5);
        assertThat(second.totalVisits()).isEqualTo(first.totalVisits());
        assertThat(first.items().getFirst().visitCount()).isEqualTo(3);
        assertThat(first.items().getFirst().storeCount()).isEqualTo(2);
        assertThat(first.items().getFirst().firstCheckinAt()).isEqualTo(NOW);
        assertThat(first.items().getFirst().lastCheckinAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(second.items().getFirst().date()).isBefore(first.items().getLast().date());
        assertThat(statistics.exportSummary(GLOBAL, null, null, null, null, null, null, null, AdminReadOptions.defaults()).items()).hasSize(55);
        assertThat(statistics.summary(GLOBAL, null, null, null, null, null, null, null, AdminReadOptions.defaults(), Integer.MAX_VALUE, 100).items()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"UTC", "Asia/Shanghai"})
    void usesChineseMidnightWithEitherJdbcConnectionTimezone(String zone) {
        connect(zone);
        Instant midnight = Instant.parse("2026-09-07T16:00:00Z");
        submission(TENANT, SALES, STORE, "北京", "旧名称", "门店", "SUBMITTED", midnight.minusSeconds(1));
        submission(TENANT, SALES, STORE, "北京", "旧名称", "门店", "SUBMITTED", midnight);
        submission(TENANT, SALES, STORE, "北京", "新名称", "门店", "SUBMITTED", midnight.plusSeconds(60));
        submission(TENANT, SALES, STORE, "深圳", "新名称", "门店", "SUBMITTED", midnight.plusSeconds(120));
        var page = statistics.summary(GLOBAL, LocalDate.of(2026,9,8), LocalDate.of(2026,9,8), null, null,
                "SUBMITTED", null, null, AdminReadOptions.defaults(), 0, 50);
        assertThat(page.totalVisits()).isEqualTo(3);
        assertThat(page.checkedInSalespeople()).isEqualTo(1);
        assertThat(page.items()).hasSize(2).allSatisfy(item -> assertThat(item.date()).isEqualTo(LocalDate.of(2026,9,8)));
        var beijing = page.items().stream().filter(item -> item.city().equals("北京")).findFirst().orElseThrow();
        assertThat(beijing.visitCount()).isEqualTo(2);
        assertThat(beijing.salespersonName()).isEqualTo("新名称");
        assertThat(beijing.firstCheckinAt()).isEqualTo(midnight);
        assertThat(beijing.lastCheckinAt()).isEqualTo(midnight.plusSeconds(60));
    }

    @Test void reusesFullFiltersIncludingLiteralSearchHistoricalVisitRankAndTenantBoundary() {
        submission(TENANT, SALES, STORE, "北京", "测试销售", "特殊%门店", "SUBMITTED", NOW.minus(2, ChronoUnit.DAYS));
        UUID target = submission(TENANT, SALES, STORE, "北京", "测试销售", "特殊%门店", "SUBMITTED", NOW);
        jdbc.update("UPDATE temp_sales_checkin_submission SET location_quality='STALE' WHERE id=?", bin(target));
        submission(TENANT, SALES, STORE_TWO, "北京", "测试销售", "特殊%门店", "SUBMITTED", NOW);
        submission(OTHER, OTHER_SALES, OTHER_STORE, "北京", "另租户销售", "特殊%门店", "SUBMITTED", NOW);
        var options = new AdminReadOptions("STALE", "PENDING", "MISSING_AUDIO", "storeName", "asc");
        LocalDate day = LocalDate.of(2026,9,8);
        var page = statistics.summary(GLOBAL, day, day, "北京", SALES, "SUBMITTED", "REVISIT", "%", options, 0, 50);
        var filters = checkins.normalizeAdminQuery(GLOBAL, day, day, "北京", SALES, "SUBMITTED", "REVISIT", "%");
        var existing = originals.adminSubmissionStats(TENANT, filters.from(), filters.toExclusive(), filters.city(),
                filters.salespersonId(), filters.status(), filters.visitType(), filters.escapedQuery(), options);
        assertThat(page.totalVisits()).isEqualTo(1).isEqualTo(existing.total());
        assertThat(page.items()).hasSize(1);
        assertThat(page.pendingReviewTotal()).isEqualTo(existing.reviewPendingTotal());
        assertThat(statistics.exportSummary(GLOBAL, null, null, null, null, null, null, null, AdminReadOptions.defaults()).totalVisits()).isEqualTo(3);
    }

    @Test void endpointEnforcesAdminScopeAndReturnsNoStoreTypedJson() throws Exception {
        submission(TENANT, SALES, STORE, "北京", "测试销售", "北京门店", "SUBMITTED", NOW);
        submission(TENANT, SALES, STORE, "深圳", "测试销售", "深圳门店", "SUBMITTED", NOW);
        String path = "/sales-checkin/admin/api/v1/submissions/attendance-summary";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(admin("北京")).param("city", "深圳")).andExpect(status().isForbidden());
        mvc.perform(get(path).with(admin("北京"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalVisits").value(1))
                .andExpect(jsonPath("$.items[0].city").value("北京"))
                .andExpect(jsonPath("$.items[0].date").value("2026-09-08"))
                .andExpect(jsonPath("$.size").value(50));
        mvc.perform(get(path).with(admin(null)).param("summaryPage", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(path).with(admin(null)).param("summarySize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(path).with(admin(null)).param("locationStatus", "INVALID")).andExpect(status().isBadRequest());
    }

    @Test void draftsAndEmptyFiltersNeverInventAttendanceOrAbsenceRows() {
        submission(TENANT, SALES, STORE, "北京", "测试销售", "草稿门店", "DRAFT", NOW);
        var draft = statistics.summary(GLOBAL, null, null, null, null, "DRAFT", null, null, AdminReadOptions.defaults(), 0, 50);
        assertThat(draft.totalVisits()).isEqualTo(1);
        assertThat(draft.checkedInSalespeople()).isZero();
        assertThat(draft.items()).isEmpty();
        var empty = statistics.summary(GLOBAL, null, null, null, null, null, null, "完全不匹配", AdminReadOptions.defaults(), 0, 50);
        assertThat(empty.totalVisits()).isZero();
        assertThat(empty.totalElements()).isZero();
        assertThat(empty.totalPages()).isZero();
        assertThat(empty.items()).isEmpty();
    }

    private RequestPostProcessor admin(String city) {
        return request -> {
            request.setAttribute(TemporaryCheckinAdminPrincipal.REQUEST_ATTRIBUTE,
                    new TemporaryCheckinAdminPrincipal(UUID.randomUUID(), UUID.randomUUID(), "stats-admin", "统计测试管理员",
                            city == null ? "GLOBAL_ADMIN" : "CITY_ADMIN", city == null ? null : UUID.randomUUID(), city, false, "fixture-csrf"));
            return request;
        };
    }

    private void salesperson(UUID tenant, UUID id, String name) {
        jdbc.update("INSERT INTO temp_sales_checkin_salesperson (id,tenant_id,name,city,employment_status,status,created_at,updated_at)"
                + " VALUES (?,?,?,'北京','在职','ACTIVE',?,?)", bin(id), bin(tenant), name, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void store(UUID tenant, UUID id) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store (id,tenant_id,client_store_id,city,attribute,name,operating_status,
                contact_name,area_range,facility_count,business_types_json,intended_businesses_json,cooperation_intent,
                tags_json,status,created_at,updated_at)
                VALUES (?,?,?,'北京','台球','测试门店','营业中','测试联系人','100平','10',JSON_ARRAY(),JSON_ARRAY(),
                '待沟通',JSON_ARRAY(),'ACTIVE',?,?)
                """, bin(id), bin(tenant), bin(UUID.randomUUID()), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private UUID submission(UUID tenant, UUID salesperson, UUID store, String city, String name, String storeName,
            String state, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission (id,tenant_id,client_submission_id,submission_key_hash,status,city,
                salesperson_id,salesperson_name_snapshot,store_id,store_name_snapshot,customer_name,visit_result,
                longitude,latitude,accuracy_meters,location_captured_at,privacy_accepted,created_at,updated_at,submitted_at,
                location_quality,review_status,storefront_photo_object_key,storefront_photo_content_type,
                storefront_photo_size_bytes,storefront_photo_sha256,storefront_photo_original_filename)
                VALUES (?,?,?,?,?,?,?,?,?,?,'测试客户','测试拜访',116,39,10,?,0,?,?,?,'GOOD','PENDING',
                'fixture/photo.jpg','image/jpeg',4,?,'测试照片.jpg')
                """, bin(id), bin(tenant), bin(UUID.randomUUID()), "a".repeat(64), state, city, bin(salesperson), name,
                bin(store), storeName, Timestamp.from(at), Timestamp.from("SUBMITTED".equals(state) ? at.minus(1, ChronoUnit.DAYS) : at),
                Timestamp.from(at), "SUBMITTED".equals(state) ? Timestamp.from(at) : null, "b".repeat(64));
        return id;
    }

    private static byte[] bin(UUID id) { return SalesUuidCodec.encode(id); }
}
