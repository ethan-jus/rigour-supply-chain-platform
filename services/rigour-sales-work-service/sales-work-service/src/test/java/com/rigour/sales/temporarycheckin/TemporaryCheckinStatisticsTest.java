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
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
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
        for(String table:List.of("review_member","review","assignment","audio","device"))
            jdbc.update("DELETE FROM temp_sales_checkin_risk_"+table);
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
                beans.getBeanProvider(org.springframework.boot.servlet.autoconfigure.MultipartProperties.class), null);
        statistics = new TemporaryCheckinStatisticsService(checkins, new TemporaryCheckinStatisticsRepository(jdbc), properties);
        var workbook = new TemporaryCheckinWorkbookService(checkins, originals, new TemporaryCheckinStatisticsRepository(jdbc),
                new TemporaryCheckinEvidenceRepository(jdbc), new TemporaryCheckinWorkbookWriter(), properties,
                new TemporaryCheckinRiskService(new TemporaryCheckinRiskRepository(jdbc),properties,Clock.systemUTC(),
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())));
        mvc = MockMvcBuilders.standaloneSetup(new TemporaryCheckinStatisticsController(statistics, new TemporaryCheckinAdminAccessPolicy()),
                        new TemporaryCheckinWorkbookController(workbook, new TemporaryCheckinAdminAccessPolicy()))
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

    @Test void audioCountCountsVisitsWithAnyAvailableRecordingNotSegmentsStoresOrFileHashes() throws Exception {
        UUID multi=submission(TENANT,SALES,STORE,"北京","测试销售","多段门店","SUBMITTED",NOW);
        UUID missingSha=submission(TENANT,SALES,STORE,"北京","测试销售","无摘要门店","SUBMITTED",NOW.plusSeconds(1));
        UUID legacy=submission(TENANT,SALES,STORE,"北京","测试销售","历史单段","SUBMITTED",NOW.plusSeconds(2));
        UUID sameFile=submission(TENANT,SALES,STORE_TWO,"北京","测试销售","相同原文件","SUBMITTED",NOW.plusSeconds(3));
        UUID partlyDeleted=submission(TENANT,SALES,STORE_TWO,"北京","测试销售","部分删除","SUBMITTED",NOW.plusSeconds(4));
        UUID deletedManifest=submission(TENANT,SALES,STORE,"北京","测试销售","清单已删除","SUBMITTED",NOW.plusSeconds(5));
        UUID deletedLegacy=submission(TENANT,SALES,STORE,"北京","测试销售","旧单段已删除","SUBMITTED",NOW.plusSeconds(6));
        UUID invalid=submission(TENANT,SALES,STORE,"北京","测试销售","无有效上传","SUBMITTED",NOW.plusSeconds(7));
        UUID draft=submission(TENANT,SALES,STORE,"北京","测试销售","有录音草稿","DRAFT",NOW);
        String sha="a".repeat(64);
        audio(multi,sha,100,false);
        appendAudioCopy(multi,false);appendAudioCopy(multi,false);
        legacyAudio(multi,false); // 已有三段清单时，首段兼容投影不可再计一次。
        audio(missingSha,null,100,false); // 有效上传不因历史 SHA 或解析时长缺失而漏计。
        legacyAudio(legacy,false);
        audio(sameFile,sha,100,false); // 相同原文件用于不同拜访，仍分别计为有录音的拜访。
        audio(partlyDeleted,sha,100,true);appendAudioCopy(partlyDeleted,false);
        audio(deletedManifest,sha,100,true);legacyAudio(deletedManifest,false);
        legacyAudio(deletedLegacy,true);
        audio(invalid,sha,0,false);
        jdbc.update("UPDATE temp_sales_checkin_submission SET audio_segments_json=JSON_ARRAY_APPEND(audio_segments_json,'$',"
                +"JSON_OBJECT('objectKey','   ','sizeBytes',100),'$',JSON_OBJECT('sizeBytes',100)) WHERE id=?",bin(invalid));
        audio(draft,sha,100,false);appendAudioCopy(draft,false);

        var page=statistics.summary(GLOBAL,null,null,null,null,null,null,null,AdminReadOptions.defaults(),0,50);
        assertThat(page.totalVisits()).isEqualTo(9);
        assertThat(page.items()).singleElement().satisfies(row->{
            assertThat(row.visitCount()).isEqualTo(8);
            assertThat(row.storeCount()).isEqualTo(2);
            assertThat(row.audioCount()).isEqualTo(5).isLessThanOrEqualTo(row.visitCount());
        });
        assertThat(statistics.summary(GLOBAL,null,null,null,null,"DRAFT",null,null,AdminReadOptions.defaults(),0,50).items()).isEmpty();
        assertThat(statistics.summary(GLOBAL,null,null,"北京",SALES,"SUBMITTED",null,"无摘要门店",AdminReadOptions.defaults(),0,50)
                .items().getFirst().audioCount()).isEqualTo(1);
        mvc.perform(get("/sales-checkin/admin/api/v1/submissions/attendance-summary").with(admin("北京")).param("status","SUBMITTED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].visitCount").value(8))
                .andExpect(jsonPath("$.items[0].audioCount").value(5));
        byte[] bytes=mvc.perform(get("/sales-checkin/admin/export.xlsx").with(admin("北京")).param("status","SUBMITTED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheet("每日销售汇总");
            assertThat(sheet.getRow(4).getCell(4).getStringCellValue()).isEqualTo("拜访门店数");
            assertThat(sheet.getRow(4).getCell(5).getStringCellValue()).isEqualTo("录音数量");
            assertThat(sheet.getRow(5).getCell(5).getNumericCellValue()).isEqualTo(5);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).contains("拜访次数","多段计一次");
        }
    }

    @Test void audioCountUsesAllMatchingVisitsBeforePaginationAndPreservesTenantCityAndDateFilters() {
        UUID second=UUID.randomUUID();salesperson(TENANT,second,"另一销售");
        UUID today=submission(TENANT,SALES,STORE,"北京","测试销售","目标门店","SUBMITTED",NOW);
        UUID yesterday=submission(TENANT,SALES,STORE,"北京","测试销售","目标门店","SUBMITTED",NOW.minus(1,ChronoUnit.DAYS));
        UUID city=submission(TENANT,SALES,STORE_TWO,"深圳","测试销售","目标门店","SUBMITTED",NOW);
        UUID person=submission(TENANT,second,STORE,"北京","另一销售","其他门店","SUBMITTED",NOW);
        UUID tenant=submission(OTHER,OTHER_SALES,OTHER_STORE,"北京","另租户销售","目标门店","SUBMITTED",NOW);
        for(UUID id:List.of(today,yesterday,city,person,tenant))audio(id,null,100,false);
        appendAudioCopy(today,false);
        var all=statistics.exportSummary(GLOBAL,null,null,null,null,"SUBMITTED",null,null,AdminReadOptions.defaults());
        assertThat(all.items()).hasSize(4).allSatisfy(row->assertThat(row.audioCount()).isEqualTo(1));
        long acrossPages=0;
        for(int page=0;page<4;page++) {
            var result=statistics.summary(GLOBAL,null,null,null,null,"SUBMITTED",null,null,AdminReadOptions.defaults(),page,1);
            assertThat(result.totalVisits()).isEqualTo(4);
            assertThat(result.totalElements()).isEqualTo(4);
            acrossPages+=result.items().getFirst().audioCount();
        }
        assertThat(acrossPages).isEqualTo(4);
        jdbc.update("UPDATE temp_sales_checkin_submission SET location_quality='STALE',review_status='APPROVED' WHERE id=?",bin(today));
        var reviewed=new AdminReadOptions("STALE","APPROVED",null,null,null);
        assertThat(statistics.summary(GLOBAL,null,null,null,null,"SUBMITTED",null,null,reviewed,0,50).items())
                .singleElement().satisfies(row->assertThat(row.audioCount()).isEqualTo(1));
        var beijing=new AdminScope(UUID.randomUUID(),"北京管理员","北京");
        LocalDate day=LocalDate.of(2026,9,8);
        var selected=statistics.summary(beijing,day,day,null,SALES,"SUBMITTED","REVISIT","目标",AdminReadOptions.defaults(),0,50);
        assertThat(selected.totalVisits()).isEqualTo(1);
        assertThat(selected.items()).singleElement().satisfies(row->{
            assertThat(row.city()).isEqualTo("北京");assertThat(row.audioCount()).isEqualTo(1);
        });
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
        mvc.perform(get(path).with(admin("北京")).param("city", "深圳")
                .param("summarySortBy", "city").param("summarySortDirection", "desc")).andExpect(status().isForbidden());
        mvc.perform(get(path).with(admin("北京")).param("summarySortBy", "salesperson")
                .param("summarySortDirection", "asc")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalVisits").value(1))
                .andExpect(jsonPath("$.items[0].city").value("北京"))
                .andExpect(jsonPath("$.items[0].date").value("2026-09-08"))
                .andExpect(jsonPath("$.size").value(50));
        mvc.perform(get(path).with(admin(null)).param("summaryPage", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(path).with(admin(null)).param("summarySize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(path).with(admin(null)).param("locationStatus", "INVALID")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({
            "date,asc,B D E F C A",
            "date,desc,E F C A B D",
            "city,asc,E F C B A D",
            "city,desc,A D E F C B",
            "salesperson,asc,E F A D C B",
            "salesperson,desc,B C E F A D"
    })
    void summaryHeadersSortAllGroupsBeforePagingAndWorkbookUsesSameOrder(String sortBy, String direction,
            String expectedLabels) throws Exception {
        Map<String, SortFixture> rows = sortingRows();
        List<String> expected = List.of(expectedLabels.split(" "));
        List<String> actual = new ArrayList<>();
        var json = new tools.jackson.databind.ObjectMapper();
        for (int page = 0; page < 3; page++) {
            var result = mvc.perform(get("/sales-checkin/admin/api/v1/submissions/attendance-summary").with(admin(null))
                    .param("status", "SUBMITTED").param("summarySortBy", sortBy).param("summarySortDirection", direction)
                    .param("summaryPage", String.valueOf(page)).param("summarySize", "2")
                    // 明细排序故意相反，不得影响每日汇总。
                    .param("sortBy", "storeName").param("sortDirection", "desc"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalVisits").value(6))
                    .andExpect(jsonPath("$.checkedInSalespeople").value(4)).andExpect(jsonPath("$.totalElements").value(6))
                    .andExpect(jsonPath("$.totalPages").value(3)).andReturn();
            for (var item : json.readTree(result.getResponse().getContentAsString()).path("items")) {
                String key = item.path("date").asText() + "|" + item.path("city").asText() + "|" + item.path("salespersonId").asText();
                actual.add(rows.entrySet().stream().filter(entry -> entry.getValue().key().equals(key))
                        .map(Map.Entry::getKey).findFirst().orElseThrow());
            }
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        var repeated = statistics.exportSummary(GLOBAL, null, null, null, null, "SUBMITTED", null, null,
                AdminReadOptions.defaults(), sortBy, direction);
        assertThat(repeated.items().stream().map(item -> item.date() + "|" + item.city() + "|" + item.salespersonId()).toList())
                .containsExactlyElementsOf(expected.stream().map(label -> rows.get(label).key()).toList());

        byte[] bytes = mvc.perform(get("/sales-checkin/admin/export.xlsx").with(admin(null))
                .param("status", "SUBMITTED").param("summarySortBy", sortBy).param("summarySortDirection", direction)
                .param("summaryPage", "2").param("summarySize", "2")
                .param("sortBy", "storeName").param("sortDirection", "desc"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var summary = book.getSheet("每日销售汇总");
            var detail = book.getSheet("打卡明细");
            var format = new DataFormatter();
            assertThat(summary.getLastRowNum()).isEqualTo(10);
            for (int index = 0; index < expected.size(); index++) {
                SortFixture fixture = rows.get(expected.get(index));
                var line = summary.getRow(index + 5);
                assertThat(format.formatCellValue(line.getCell(0))).isEqualTo(fixture.date().toString());
                assertThat(line.getCell(1).getStringCellValue()).isEqualTo(fixture.city());
                assertThat(line.getCell(2).getStringCellValue()).isEqualTo(fixture.name());
            }
            // 真正重开Excel验证明细仍按原storeName降序；不是随汇总一起倒序。
            List<String> detailNames = new ArrayList<>();
            for (int index = 5; index <= detail.getLastRowNum(); index++)
                detailNames.add(detail.getRow(index).getCell(3).getStringCellValue());
            assertThat(detailNames).containsExactly("F门店", "E门店", "D门店", "C门店", "B门店", "A门店");
        }
    }

    @Test void rejectsUnknownSummarySortFieldsOnBothEndpoints() throws Exception {
        for (String path : List.of("/sales-checkin/admin/api/v1/submissions/attendance-summary", "/sales-checkin/admin/export.xlsx")) {
            mvc.perform(get(path).with(admin(null)).param("summarySortBy", "completedAt"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("summarySortBy无效"));
            mvc.perform(get(path).with(admin(null)).param("summarySortDirection", "descending"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("summarySortDirection无效"));
            mvc.perform(get(path).param("summarySortBy", "city").param("summarySortDirection", "desc"))
                    .andExpect(status().isUnauthorized());
        }
    }

    private Map<String, SortFixture> sortingRows() {
        UUID second = UUID.fromString("20000000-0000-0000-0000-000000000003");
        UUID third = UUID.fromString("20000000-0000-0000-0000-000000000004");
        UUID sameName = UUID.fromString("20000000-0000-0000-0000-000000000005");
        salesperson(TENANT, second, "王五"); salesperson(TENANT, third, "李四"); salesperson(TENANT, sameName, "另一个张三");
        Map<String, SortFixture> rows = new LinkedHashMap<>();
        rows.put("A", new SortFixture(LocalDate.of(2026,9,9), "深圳", SALES, "张三"));
        rows.put("B", new SortFixture(LocalDate.of(2026,9,8), "北京", second, "王五"));
        rows.put("C", new SortFixture(LocalDate.of(2026,9,9), "北京", third, "李四"));
        rows.put("D", new SortFixture(LocalDate.of(2026,9,8), "深圳", SALES, "张三"));
        rows.put("E", new SortFixture(LocalDate.of(2026,9,9), "北京", SALES, "张三"));
        rows.put("F", new SortFixture(LocalDate.of(2026,9,9), "北京", sameName, "张三"));
        rows.forEach((label, row) -> submission(TENANT, row.salesperson(), STORE, row.city(), row.name(), label + "门店",
                "SUBMITTED", row.date().atTime(10,0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        submission(OTHER, OTHER_SALES, OTHER_STORE, "北京", "越权销售", "Z越权门店", "SUBMITTED", NOW.plus(3, ChronoUnit.DAYS));
        return rows;
    }

    private record SortFixture(LocalDate date, String city, UUID salesperson, String name) {
        String key() { return date + "|" + city + "|" + salesperson; }
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

    @Test void adminListAndExportUseSavedFormattedAddressWithoutChangingCoordinates() {
        UUID id=submission(TENANT,SALES,STORE,"北京","测试销售","示例门店","SUBMITTED",NOW);
        for(String shortAddress:java.util.Arrays.asList("旧简写地址",null)) {
            jdbc.update("UPDATE temp_sales_checkin_submission SET location_address=?,location_formatted_address=? WHERE id=?",
                    shortAddress,"北京市测试区设备街88号",bin(id));
            assertThat(originals.findAdminSubmission(TENANT,id,null).orElseThrow().locationAddress()).isEqualTo("北京市测试区设备街88号");
            assertThat(originals.exportForWorkbook(TENANT,null,null,null,null,null,null,null,10,AdminReadOptions.defaults())
                    .getFirst().locationAddress()).isEqualTo("北京市测试区设备街88号");
            assertThat(jdbc.queryForObject("SELECT location_address FROM temp_sales_checkin_submission WHERE id=?",String.class,bin(id))).isEqualTo(shortAddress);
        }
    }

    @Test void sharedDeviceFiltersKeepAuthorizedHistoryOutsideSelectedCityAndSalesperson() throws Exception {
        UUID second=UUID.randomUUID();salesperson(TENANT,second,"另一销售");
        UUID a=submission(TENANT,SALES,STORE,"北京","测试销售","北京门店","SUBMITTED",NOW);
        UUID b=submission(TENANT,second,STORE_TWO,"深圳","另一销售","深圳门店","SUBMITTED",NOW.minus(2,ChronoUnit.DAYS));
        UUID other=submission(OTHER,OTHER_SALES,OTHER_STORE,"北京","别租户销售","其他门店","SUBMITTED",NOW);
        for(UUID id:List.of(a,b,other)) jdbc.update("UPDATE temp_sales_checkin_submission SET device_token_hash=? WHERE id=?","d".repeat(64),bin(id));
        registerRiskFixtures();
        var options=riskOptions(null,"SHARED",null,null,null);
        assertThat(originals.findAdminSubmissions(TENANT,null,null,"北京",SALES,"SUBMITTED",null,null,0,20,options))
                .extracting(TemporaryCheckinRepository.AdminSubmissionRow::id).containsExactly(a);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,"北京",SALES,"SUBMITTED",null,null,options).total()).isEqualTo(1);
        assertThat(originals.exportForWorkbook(TENANT,null,null,"北京",SALES,"SUBMITTED",null,null,100,options))
                .extracting(TemporaryCheckinRepository.ExportRow::id).containsExactly(a);
        assertThat(statistics.summary(GLOBAL,LocalDate.of(2026,9,8),LocalDate.of(2026,9,8),"北京",SALES,
                "SUBMITTED",null,null,options,0,50).totalVisits()).isEqualTo(1);
        var cityAdmin=new AdminScope(UUID.randomUUID(),"北京管理员","北京");
        assertThat(statistics.summary(cityAdmin,null,null,"北京",SALES,"SUBMITTED",null,null,options,0,50).totalVisits()).isZero();
        mvc.perform(get("/sales-checkin/admin/api/v1/submissions/attendance-summary").with(admin(null))
                .param("city","北京").param("deviceRisk","SHARED").param("salespersonId",SALES.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalVisits").value(1));
        mvc.perform(get("/sales-checkin/admin/api/v1/submissions/attendance-summary").with(admin("北京"))
                .param("deviceRisk","SHARED")).andExpect(status().isOk()).andExpect(jsonPath("$.totalVisits").value(0));
        byte[] exported=mvc.perform(get("/sales-checkin/admin/export.xlsx").with(admin(null))
                .param("city","北京").param("salespersonId",SALES.toString()).param("deviceRisk","SHARED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(4);
            assertThat(book.getSheet("打卡明细").getLastRowNum()).isEqualTo(5);
            var related=book.getSheet("设备关联");
            assertThat(related.getLastRowNum()).isEqualTo(6);
            assertThat(related.getRow(5).getCell(4).getStringCellValue()).isEqualTo("另一销售");
            assertThat(related.getRow(5).getCell(1).getStringCellValue()).isEqualTo("否");
            assertThat(related.getRow(6).getCell(1).getStringCellValue()).isEqualTo("是");
            assertThat(related.getRow(5).getCell(11).getNumericCellValue()).isEqualTo(2);
            assertThat(related.getRow(5).getCell(12).getNumericCellValue()).isEqualTo(1);
            assertThat(new DataFormatter().formatCellValue(related.getRow(6).getCell(2))).isEqualTo("2026-09-08 10:00:00");
        }
        byte[] scoped=mvc.perform(get("/sales-checkin/admin/export.xlsx").with(admin("北京")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(scoped))) {
            var related=book.getSheet("设备关联");
            assertThat(related.getLastRowNum()).isEqualTo(5);
            assertThat(related.getRow(5).getCell(4).getStringCellValue()).isEqualTo("测试销售");
            assertThat(related.getRow(5).getCell(10).getNumericCellValue()).isEqualTo(1);
        }
    }

    @Test void audioFiltersUseBytesAndDistinctVisitsRatherThanNamesOrDurations() throws Exception {
        UUID second=UUID.randomUUID();salesperson(TENANT,second,"另一销售");
        UUID a=submission(TENANT,SALES,STORE,"北京","测试销售","首店","SUBMITTED",NOW);
        UUID b=submission(TENANT,second,STORE_TWO,"北京","另一销售","次店","SUBMITTED",NOW.minus(1,ChronoUnit.DAYS));
        UUID sameName=submission(TENANT,SALES,STORE,"北京","测试销售","同名异文件","SUBMITTED",NOW.plusSeconds(30));
        UUID wrongSize=submission(TENANT,SALES,STORE,"北京","测试销售","不同字节数","SUBMITTED",NOW.plusSeconds(60));
        UUID removed=submission(TENANT,SALES,STORE,"北京","测试销售","已删录音","SUBMITTED",NOW.plusSeconds(90));
        UUID draft=submission(TENANT,SALES,STORE,"北京","测试销售","草稿录音","DRAFT",NOW);
        audio(a,"a".repeat(64),1000,false);audio(b,"a".repeat(64),1000,false);
        audio(sameName,"b".repeat(64),1000,false);audio(wrongSize,"a".repeat(64),999,false);
        audio(removed,"a".repeat(64),1000,true);audio(draft,"a".repeat(64),1000,false);
        jdbc.update("UPDATE temp_sales_checkin_submission SET audio_segments_json=JSON_ARRAY_APPEND(audio_segments_json,'$',"
                +"JSON_SET(JSON_EXTRACT(audio_segments_json,'$[0]'),'$.segmentId',?)) WHERE id=?",UUID.randomUUID().toString(),bin(a));
        registerRiskFixtures();
        var options=riskOptions("HIGH",null,"DUPLICATE",null,null);
        assertThat(originals.findAdminSubmissions(TENANT,null,null,null,null,null,null,null,0,20,options))
                .extracting(TemporaryCheckinRepository.AdminSubmissionRow::id).containsExactlyInAnyOrder(a,b);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,null,null,null,options).total()).isEqualTo(2);
        var audioSummary=statistics.summary(GLOBAL,null,null,null,null,null,null,null,options,0,50);
        assertThat(audioSummary.totalVisits()).isEqualTo(2);
        assertThat(audioSummary.items().stream().mapToLong(TemporaryCheckinStatisticsRepository.DailyAttendance::audioCount).sum()).isEqualTo(2);
        assertThat(originals.exportForWorkbook(TENANT,null,null,null,null,null,null,null,100,options)).hasSize(2);
        byte[] exported=mvc.perform(get("/sales-checkin/admin/export.xlsx").with(admin(null)).param("riskLevel","HIGH")
                .param("audioRisk","CROSS_SALES").param("salespersonId",SALES.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            assertThat(book.getSheet("打卡明细").getLastRowNum()).isEqualTo(5);
            var audio=book.getSheet("录音关联");
            assertThat(audio.getLastRowNum()).isEqualTo(6);
            assertThat(audio.getRow(5).getCell(1).getStringCellValue()).isEqualTo("a".repeat(64));
            assertThat(audio.getRow(5).getCell(16).getNumericCellValue()).isEqualTo(2);
            assertThat(audio.getRow(5).getCell(17).getNumericCellValue()).isEqualTo(1);
            assertThat(audio.getRow(6).getCell(14).getNumericCellValue()).isEqualTo(2);
            assertThat(audio.getRow(6).getCell(18).getNumericCellValue()).isEqualTo(1);
            assertThat(audio.getRow(6).getCell(19).getNumericCellValue()).isEqualTo(1);
        }
        jdbc.update("UPDATE temp_sales_checkin_submission SET risk_level='HIGH' WHERE id=?",bin(sameName));
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,null,null,null,riskOptions("HIGH",null,null,null,null)).total()).isEqualTo(3);
    }

    @Test void groupReviewCannotCoverNewMembersOrOtherScopes() {
        UUID second=UUID.randomUUID();salesperson(TENANT,second,"另一销售");
        UUID a=submission(TENANT,SALES,STORE,"北京","测试销售","首店","SUBMITTED",NOW);
        UUID b=submission(TENANT,second,STORE_TWO,"北京","另一销售","次店","SUBMITTED",NOW.plusSeconds(1));
        for(UUID id:List.of(a,b))jdbc.update("UPDATE temp_sales_checkin_submission SET device_token_hash=? WHERE id=?","e".repeat(64),bin(id));
        registerRiskFixtures();
        Long group=jdbc.queryForObject("SELECT id FROM temp_sales_checkin_risk_device WHERE tenant_id=? AND token_hash=?",Long.class,bin(TENANT),"e".repeat(64));
        UUID review=UUID.randomUUID();
        jdbc.update("INSERT INTO temp_sales_checkin_risk_review(id,tenant_id,group_kind,group_id,scope_key,client_event_id,evidence_version,"
                +"rules_version,status,note,actor,reviewed_at,member_count,request_hash) VALUES (?,?,'DEVICE',?,'ALL',?,?,?,'EXPLAINED','已核对共用','总部管理员',?,2,?)",
                bin(review),bin(TENANT),group,bin(UUID.randomUUID()),"a".repeat(64),TemporaryCheckinRiskModels.RULES_VERSION,Timestamp.from(NOW),"f".repeat(64));
        for(UUID id:List.of(a,b))jdbc.update("INSERT INTO temp_sales_checkin_risk_review_member VALUES (?,?,LOWER(HEX(?)))",bin(TENANT),bin(review),bin(id));
        var pending=riskOptions(null,null,null,"PENDING",null);
        var explained=riskOptions(null,null,null,"EXPLAINED",null);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,null,null,null,explained).total()).isEqualTo(2);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,null,null,null,pending).total()).isZero();
        assertThat(originals.adminSubmissionStats(TENANT,null,null,"北京",null,null,null,null,pending.withScope("北京")).total()).isEqualTo(2);
        UUID next=submission(TENANT,second,STORE,"北京","另一销售","新增拜访","SUBMITTED",NOW.plusSeconds(2));
        jdbc.update("UPDATE temp_sales_checkin_submission SET device_token_hash=? WHERE id=?","e".repeat(64),bin(next));
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,null,null,null,pending).total()).isEqualTo(3);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,null,null,null,explained).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM temp_sales_checkin_risk_review WHERE id=?",String.class,bin(review))).isEqualTo("EXPLAINED");
    }

    @Test void riskParametersAreValidatedAndSortingRunsBeforePagination() throws Exception {
        for(String path:List.of("/sales-checkin/admin/api/v1/submissions/attendance-summary","/sales-checkin/admin/export.xlsx")) {
            mvc.perform(get(path).with(admin(null)).param("riskLevel","HIGH OR 1=1")).andExpect(status().isBadRequest());
            mvc.perform(get(path).with(admin(null)).param("riskFlags","arbitrary_flag")).andExpect(status().isBadRequest());
            mvc.perform(get(path).with(admin(null)).param("riskQuery","DEV-%")).andExpect(status().isBadRequest());
        }
        UUID a=submission(TENANT,SALES,STORE,"北京","测试销售","单次设备","SUBMITTED",NOW);
        UUID b=submission(TENANT,SALES,STORE_TWO,"北京","测试销售","多次设备","SUBMITTED",NOW.minusSeconds(1));
        UUID c=submission(TENANT,SALES,STORE_TWO,"北京","测试销售","多次设备","SUBMITTED",NOW.minusSeconds(2));
        jdbc.update("UPDATE temp_sales_checkin_submission SET device_token_hash=? WHERE id=?","a".repeat(64),bin(a));
        for(UUID id:List.of(b,c))jdbc.update("UPDATE temp_sales_checkin_submission SET device_token_hash=? WHERE id=?","c".repeat(64),bin(id));
        registerRiskFixtures();
        var sorted=new AdminReadOptions(null,null,null,"deviceVisitCount","desc");
        assertThat(originals.findAdminSubmissions(TENANT,null,null,null,null,null,null,null,0,1,sorted).getFirst().id()).isIn(b,c);
        assertThat(originals.findAdminSubmissions(TENANT,null,null,null,null,null,null,null,2,1,sorted).getFirst().id()).isEqualTo(a);
    }

    private AdminReadOptions riskOptions(String level,String device,String audio,String review,String scope) {
        return new AdminReadOptions(null,null,null,null,null,level,List.of(),device,audio,null,review,scope);
    }
    private void registerRiskFixtures() {
        jdbc.update("INSERT IGNORE INTO temp_sales_checkin_risk_device(tenant_id,token_hash,created_at) SELECT tenant_id,device_token_hash,UTC_TIMESTAMP(6)"
                +" FROM temp_sales_checkin_submission WHERE device_token_hash IS NOT NULL GROUP BY tenant_id,device_token_hash");
        jdbc.update("INSERT IGNORE INTO temp_sales_checkin_risk_audio(tenant_id,sha256,size_bytes,created_at) SELECT tenant_id,sha256,size_bytes,UTC_TIMESTAMP(6)"
                +" FROM temp_sales_checkin_risk_audio_source GROUP BY tenant_id,sha256,size_bytes");
    }
    private void audio(UUID id,String sha,long size,boolean deleted) {
        jdbc.update("UPDATE temp_sales_checkin_submission SET audio_segments_json=JSON_ARRAY(JSON_OBJECT('segmentId',?,'sha256',?,'sizeBytes',?,"
                +"'objectKey','fixture/test.mp3','originalFilename','相同文件名.mp3','contentType','audio/mpeg','clientDurationMs',20000,'deletedAt',?)) WHERE id=?",
                UUID.randomUUID().toString(),sha,size,deleted?"2026-09-08T02:00:00Z":null,bin(id));
    }
    private void appendAudioCopy(UUID id,boolean deleted) {
        jdbc.update("UPDATE temp_sales_checkin_submission SET audio_segments_json=JSON_ARRAY_APPEND(audio_segments_json,'$',"
                +"JSON_SET(JSON_EXTRACT(audio_segments_json,'$[0]'),'$.segmentId',?,'$.deletedAt',?)) WHERE id=?",
                UUID.randomUUID().toString(),deleted?"2026-09-08T02:00:00Z":null,bin(id));
    }
    private void legacyAudio(UUID id,boolean deleted) {
        jdbc.update("UPDATE temp_sales_checkin_submission SET audio_object_key='fixture/legacy.mp3',audio_content_type='audio/mpeg',"
                +"audio_size_bytes=100,audio_sha256=?,audio_original_filename='历史录音.mp3',audio_deleted_at=?,audio_deleted_by=?,audio_deletion_reason=? WHERE id=?",
                "c".repeat(64),deleted?Timestamp.from(NOW):null,deleted?"统计测试管理员":null,deleted?"测试已删除录音不计入汇总":null,bin(id));
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
