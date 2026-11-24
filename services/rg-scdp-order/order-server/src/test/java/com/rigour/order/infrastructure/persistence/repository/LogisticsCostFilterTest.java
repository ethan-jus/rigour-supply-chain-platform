package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.api.v1.model.LogisticsCostQuery;
import com.rigour.order.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;

/** 在隔离 MySQL 执行真实 MyBatis 查询，验证 JSON_TABLE、字段交集及权限。 */
@Testcontainers(disabledWithoutDocker = true)
public class LogisticsCostFilterTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4").withUsername("root").withPassword("fixture-password");

    static final JsonMapper JSON = JsonMapper.builder().build();
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusOrderLogisticsCostRepository store;

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword(),
                        true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "DROP TABLE IF EXISTS order_logistics_cost, order_logistics_import_order,"
                        + " order_transport_shipment_source");
        String ddl =
                Files.readString(
                        Path.of("src/main/resources/db/migration/V54__order_logistics_cost.sql"));
        ddl = ddl.substring(ddl.indexOf("CREATE TABLE order_logistics_cost ("));
        ddl = ddl.substring(0, ddl.indexOf(';') + 1);
        jdbc.execute(ddl);
        jdbc.execute("ALTER TABLE order_logistics_cost DROP CHECK ck_cost_status");
        jdbc.execute(
                "ALTER TABLE order_logistics_cost ADD CONSTRAINT ck_cost_status CHECK(status_code"
                        + " IN ('ACTIVE','VOID'))");
        jdbc.execute("ALTER TABLE order_logistics_cost ADD COLUMN deleted INT NOT NULL DEFAULT 0");

        jdbc.execute("ALTER TABLE order_logistics_cost ADD COLUMN source_system VARCHAR(20)");
        jdbc.execute("ALTER TABLE order_logistics_cost ADD COLUMN tracking_no VARCHAR(100)");
        jdbc.execute(
                "CREATE TABLE order_logistics_import_order(id VARCHAR(50) PRIMARY KEY, tenant_id"
                        + " VARCHAR(50),cost_id VARCHAR(50),source_json LONGTEXT,latest_source_json"
                        + " LONGTEXT,source_channel VARCHAR(16) DEFAULT 'API') DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        jdbc.execute(
                "CREATE TABLE order_transport_shipment_source(tenant_id VARCHAR(50),cost_id"
                        + " VARCHAR(50),latest_json LONGTEXT) DEFAULT CHARSET=utf8mb4"
                        + " COLLATE=utf8mb4_general_ci");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OrderLogisticsCostMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var scope = mock(LogisticsWarehouseDataScope.class);
        when(scope.predicate(anyString(), any(String[].class)))
                .thenReturn(
                        new LogisticsWarehouseDataScope.Predicate("warehouse_id=?", List.of(7L)));
        when(scope.manualPredicate(anyString(), anyString()))
                .thenReturn(
                        new LogisticsWarehouseDataScope.Predicate("created_by=?", List.of("U")));
        store =
                new MybatisPlusOrderLogisticsCostRepository(
                        session.getMapper(OrderLogisticsCostMapper.class),
                        mock(OrderLogisticsCostAuditMapper.class),
                        JSON,
                        scope,
                        mock(com.rigour.order.application.port.out.OrderLogisticsReferences.class));
        insert("good", "T1", "DOC-A", 7L);
        insert("other", "T2", "DOC-A", 7L);
        insert("hidden", "T1", "DOC-H", 8L);
        jdbc.update("UPDATE order_logistics_cost SET source_warehouse_id=warehouse_id");
    }

    @Test
    void platformOrderPlacementTimeOverridesTransportDateAndReverseIngestionOrder() {
        insert("early", "T1", "EARLY", 7L);
        insert("late", "T1", "LATE", 7L);
        insert("manual", "T1", "MANUAL", 7L);
        jdbc.update("UPDATE order_logistics_cost SET source_warehouse_id=warehouse_id");
        jdbc.update("UPDATE order_logistics_cost SET created_time='2026-11-12 12:00:00'");
        jdbc.update(
                "UPDATE order_logistics_cost SET created_time='2026-11-12 13:00:00' WHERE"
                        + " id='early'");
        jdbc.update(
                "INSERT INTO order_logistics_import_order(id,tenant_id,cost_id,source_json,latest_source_json)"
                        + " VALUES('different-early-source','T1','early',?,NULL)",
                "{\"raw\":{\"下单时间\":\"2026-10-01 10:00:00\"},\"useTime\":\"2026-11-20 10:00:00\"}");
        jdbc.update(
                "INSERT INTO order_logistics_import_order(id,tenant_id,cost_id,source_json,latest_source_json)"
                        + " VALUES('different-late-source','T1','late',?,?)",
                "{\"raw\":{\"下单时间\":\"2026-10-01 09:00:00\"}}",
                "{\"raw\":{\"下单时间\":\"2026-10-02 17:00:00\"},\"useTime\":\"2026-09-01 17:00:00\"}");
        // 相同费用 ID 的其他租户来源不得影响本租户排序。
        jdbc.update(
                "INSERT INTO order_logistics_import_order(id,tenant_id,cost_id,source_json,latest_source_json)"
                        + " VALUES('manual','T2','manual',?,NULL)",
                "{\"raw\":{\"下单时间\":\"2026-10-01 23:00:00\"}}");
        var first = JSON.readValue("{\"begin\":0,\"step\":1}", LogisticsCostQuery.class);
        var second = JSON.readValue("{\"begin\":1,\"step\":1}", LogisticsCostQuery.class);
        assertThat(store.entries("T1", "LOGISTICS", first, "cost:read").items())
                .extracting(r -> r.id())
                .containsExactly("late");
        assertThat(store.entries("T1", "LOGISTICS", second, "cost:read").items())
                .extracting(r -> r.id())
                .containsExactly("early");
        var all = JSON.readValue("{\"begin\":0,\"step\":20}", LogisticsCostQuery.class);
        assertThat(store.entries("T1", "LOGISTICS", all, "cost:read").items())
                .extracting(r -> r.id())
                .containsExactly("late", "early", "manual", "good");
    }

    @Test
    void manualShipmentDateOverridesBackfillCreationTimeAcrossPages() {
        insert("aaa-new", "T1", "NEW-ARRIVAL", 7L);
        insert("zzz-old", "T1", "OLDER-ARRIVAL", 7L);
        insert("zzz-backfill", "T1", "HISTORICAL-BACKFILL", 7L);
        jdbc.update("UPDATE order_logistics_cost SET created_time='2026-11-11 10:00:00'");
        jdbc.update(
                "UPDATE order_logistics_cost SET created_time='2026-11-12 09:49:00' WHERE"
                        + " id='aaa-new'");
        jdbc.update(
                "UPDATE order_logistics_cost SET cost_date='2026-09-30',created_time='2026-11-12"
                        + " 10:00:00' WHERE id='zzz-backfill'");
        jdbc.update("UPDATE order_logistics_cost SET source_warehouse_id=warehouse_id");
        var first = JSON.readValue("{\"begin\":0,\"step\":1}", LogisticsCostQuery.class);
        var second = JSON.readValue("{\"begin\":1,\"step\":1}", LogisticsCostQuery.class);
        assertThat(store.entries("T1", "LOGISTICS", first, "cost:read").items())
                .extracting(r -> r.id())
                .containsExactly("aaa-new");
        assertThat(store.entries("T1", "LOGISTICS", second, "cost:read").items())
                .extracting(r -> r.id())
                .containsExactly("zzz-old");
        var all = JSON.readValue("{\"begin\":0,\"step\":20}", LogisticsCostQuery.class);
        assertThat(store.entries("T1", "LOGISTICS", all, "cost:read").items())
                .extracting(r -> r.id())
                .containsExactly("aaa-new", "zzz-old", "good", "zzz-backfill");
    }

    @Test
    void dhbShipmentInstantUsesBusinessTimeZoneAndEarliestDepartureOfTheSameTransport() {
        insert("dhb", "T1", "DHB", 7L);
        insert("manual", "T1", "MANUAL", 7L);
        jdbc.update(
                "UPDATE order_logistics_cost SET"
                        + " source_warehouse_id=warehouse_id,cost_date='2026-11-16'");
        jdbc.update(
                "INSERT INTO order_transport_shipment_source"
                        + " VALUES('T1','dhb',?),('T1','dhb',?),('T2','manual',?)",
                "{\"shipTime\":\"2026-11-15T17:00:00Z\"}",
                "{\"shipTime\":\"2026-11-16T06:00:00Z\"}",
                "{\"shipTime\":\"2027-01-01T00:00:00Z\"}");
        var q = JSON.readValue("{\"begin\":0,\"step\":20}", LogisticsCostQuery.class);
        assertThat(store.entries("T1", "LOGISTICS", q, "cost:read").items())
                .extracting(r -> r.id())
                .containsExactly("dhb", "manual", "good");
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT "
                                                + MybatisPlusOrderLogisticsCostRepository
                                                        .transportTimeSql()
                                                + " FROM order_logistics_cost WHERE id='dhb'",
                                        java.sql.Timestamp.class)
                                .toLocalDateTime())
                .isEqualTo(java.time.LocalDateTime.of(2026, 11, 16, 1, 0));
    }

    @Test
    void senderReceiverAndCarrierAreIndependentAndIntersect() {
        jdbc.update(
                "UPDATE order_logistics_cost SET data_json=?",
                "{\"logistics\":{\"senderName\":\"发货甲\",\"receiverName\":\"收货乙\",\"carrierName\":\"承运丙\"}}");
        var match =
                JSON.readValue(
                        "{\"begin\":0,\"step\":20,\"senderName\":\"发货甲\",\"receiverName\":\"收货乙\",\"carrierName\":\"承运丙\"}",
                        LogisticsCostQuery.class);
        var result = store.entries("T1", "LOGISTICS", match, "cost:read");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.registeredAmount()).isEqualByComparingTo("100");
        var crossed =
                JSON.readValue(
                        "{\"begin\":0,\"step\":20,\"senderName\":\"收货乙\",\"receiverName\":\"发货甲\"}",
                        LogisticsCostQuery.class);
        assertThat(store.entries("T1", "LOGISTICS", crossed, "cost:read").total()).isZero();
    }

    @Test
    void relatedCostCountsRemainScopedAndDeduplicateAllocations() {
        assertThat(store.sourceUsage("T1", List.of("SRC-A"), "cost:read"))
                .containsEntry("SRC-A", 1);
        jdbc.update(
                "UPDATE order_logistics_cost SET sources_json=? WHERE id='good'",
                "[{\"orderCode\":\"SRC-A\"},{\"orderCode\":\"SRC-A\"}]");
        assertThat(store.sourceUsage("T1", List.of("SRC-A"), "cost:read"))
                .containsEntry("SRC-A", 1);
        jdbc.update("UPDATE order_logistics_cost SET status_code='VOID' WHERE id='good'");
        assertThat(store.sourceUsage("T1", List.of("SRC-A"), "cost:read")).isEmpty();
    }

    @Test
    void businessOwnershipFiltersUseFrozenSourcesInsteadOfBookingOwner() {
        jdbc.update(
                "UPDATE order_logistics_cost SET data_json=?",
                "{\"ledger\":{\"schemaVersion\":2,\"sources\":[{\"employeeName\":\"销售张三\",\"departmentName\":\"武汉销售部\"}]}}");
        var match =
                JSON.readValue(
                        "{\"begin\":0,\"step\":20,\"sourceEmployeeName\":\"张三\",\"sourceDepartmentName\":\"武汉\"}",
                        LogisticsCostQuery.class);
        assertThat(store.entries("T1", "LOGISTICS", match, "cost:read").total()).isEqualTo(1);
        var wrong =
                JSON.readValue(
                        "{\"begin\":0,\"step\":20,\"sourceEmployeeName\":\"下单人李四\"}",
                        LogisticsCostQuery.class);
        assertThat(store.entries("T1", "LOGISTICS", wrong, "cost:read").total()).isZero();
    }

    @Test
    void transportPlacerAndBusinessNumberUseTheDisplayedSourceBeforePagination() {
        jdbc.execute("ALTER TABLE order_logistics_import_order ADD business_json JSON");
        insert("platform", "T1", "HLL", 7L);
        jdbc.update("UPDATE order_logistics_cost SET source_warehouse_id=warehouse_id");
        jdbc.update("""
                UPDATE order_logistics_cost SET data_json=?
                WHERE id IN ('good','other','hidden','platform')
                """, """
                {"ledger":{"schemaVersion":2,"sources":[
                  {"orderTypeCode":"SALES_ORDER","orderCode":"XS_001","employeeCode":"E1","employeeName":"销售张三","departmentName":"武汉销售部"},
                  {"orderTypeCode":"SALES_ORDER","orderCode":"XS002","employeeCode":"E2","employeeName":"销售张三","departmentName":"北京销售部"},
                  {"orderTypeCode":"TRANSFER_ORDER","orderCode":"DB001","employeeCode":"E3","employeeName":"调拨员","departmentName":"仓储部"}
                ]}}
                """);
        jdbc.update("""
                INSERT INTO order_logistics_import_order(id,tenant_id,cost_id,source_channel,source_json,business_json)
                VALUES('source','T1','platform','API',?,?)
                """, """
                {"raw":{"下单人(员工)":"平台王五"},"facts":{"sourceEmployeeName":" ","sourceDepartmentName":"平台部门",
                  "mappedEmployeeCode":"E4","mappedDepartmentName":"本系统运营部"}}
                """, "{\"orderCodes\":[\"HLL-SALES-2\",\"HLL-SALES-1\"]}");
        assertThat(transportIds("\"orderPlacerEmployeeCode\":\"E1\",\"orderPlacerDepartment\":\"武汉销售部\",\"businessOrderNo\":\"XS_0\""))
                .containsExactly("good");
        assertThat(transportIds("\"orderPlacerEmployeeCode\":\"E1\",\"orderPlacerDepartment\":\"北京销售部\"")).isEmpty();
        assertThat(transportIds("\"orderPlacerEmployeeCode\":\"E2\",\"orderPlacerDepartment\":\"北京销售部\"")).containsExactly("good");
        assertThat(transportIds("\"orderPlacerEmployeeCode\":\"E3\"")).isEmpty();
        assertThat(transportIds("\"orderPlacerEmployeeCode\":\"E4\",\"orderPlacerDepartment\":\"本系统运营部\",\"businessOrderNo\":\"HLL-SALES-1\""))
                .containsExactly("platform");
        assertThat(transportIds("\"orderPlacerDepartment\":\"平台部门\"")).isEmpty();
        assertThat(transportIds("\"orderPlacerEmployeeCode\":\"E4\",\"businessOrderNo\":\"XS_001\"")).isEmpty();

        jdbc.update("INSERT INTO order_transport_shipment_source VALUES('T2','platform','{}')");
        String salesFilters =
                "\"orderPlacerEmployeeCode\":\"E1\",\"orderPlacerDepartment\":\"武汉销售部\","
                        + "\"businessOrderNo\":\"HLL-SALES-1\"";
        assertThat(transportIds(salesFilters)).isEmpty();

        jdbc.update("INSERT INTO order_transport_shipment_source VALUES('T1','platform','{}')");
        assertThat(transportIds(salesFilters)).containsExactly("platform");
        assertThat(transportIds(
                        "\"orderPlacerEmployeeCode\":\"E4\",\"orderPlacerDepartment\":\"本系统运营部\","
                                + "\"businessOrderNo\":\"HLL-SALES-1\""))
                .containsExactly("platform");
        assertThat(transportIds(
                        "\"orderPlacerEmployeeCode\":\"E1\",\"orderPlacerDepartment\":\"本系统运营部\""))
                .isEmpty();
    }

    private List<String> transportIds(String filters) {
        var q = JSON.readValue("{\"begin\":0,\"step\":1," + filters + "}", LogisticsCostQuery.class);
        var result = store.entries("T1", "LOGISTICS", q, "cost:read");
        assertThat(result.total()).isEqualTo(result.items().size());
        return result.items().stream().map(row -> row.id()).toList();
    }

    @Test
    void selectedExportStillIntersectsTenantAndWarehouseScope() {
        var rows = store.selectedEntries("T1", List.of("good", "other", "hidden"), "cost:export");
        assertThat(rows).extracting(r -> r.id()).containsExactly("good");
        assertThat(store.selectedEntries("T1", List.of("good' OR 1=1 --"), "cost:export"))
                .isEmpty();
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    void insert(String id, String tenant, String document, Object owner) {
        jdbc.update(
                "INSERT INTO"
                    + " order_logistics_cost(id,tenant_id,request_id,document_no,title,type_code,city_code,cost_date,amount,payment_status_code,status_code,data_json,created_by,updated_by,category_code,warehouse_id,sources_json)"
                    + " VALUES(?,?,?,?,'差旅住宿','TRAVEL','CITY','2026-10-01',100,'PAID','ACTIVE',?,'U','U','LOGISTICS',?,"
                    + " '[{\"documentNo\":\"SRC-A\",\"senderName\":\"SENDER-ONLY\"}]')",
                id,
                tenant,
                id,
                document,
                JSON.writeValueAsString(
                        Map.of("remark", "发票100%", "logistics", Map.of("trackingNo", "WAY-A"))),
                owner);
    }

    long count(String document, String title, String remark, String tracking, String source) {
        var filter =
                new LogisticsCostQuery(
                        0, 20, document, title, remark, tracking, source, null, null, null, null,
                        null, null, null, null);
        var result = store.entries("T1", "LOGISTICS", filter, "cost:read");
        assertThat(result.items()).hasSize((int) result.total());
        return result.total();
    }

    @Test
    void independentFieldsIntersectWithinTenantAndScope() {
        assertThat(count(null, null, null, null, null)).isEqualTo(1);
        assertThat(count(" DOC- ", "住宿", "发票", null, null)).isEqualTo(1);
        assertThat(count("住宿", null, null, null, null)).isZero();
        assertThat(count(null, "DOC-", null, null, null)).isZero();
        assertThat(count("DOC-A", "不匹配", null, null, null)).isZero();
    }

    @Test
    void remarksSearchTheirOwnJsonFieldAndTreatWildcardCharactersLiterally() {
        assertThat(count(null, null, "100%", null, null)).isEqualTo(1);
        assertThat(count(null, null, "住宿", null, null)).isZero();
        assertThat(count("%", null, null, null, null)).isZero();
    }

    @Test
    void trackingAndSourceDocumentAreSeparateFromOtherSnapshotFields() {
        assertThat(count(null, null, null, "WAY", "SRC")).isEqualTo(1);
        assertThat(count(null, null, null, "SRC", null)).isZero();
        assertThat(count(null, null, null, null, "SENDER-ONLY")).isZero();
        assertThat(count(null, null, null, null, "%")).isZero();
    }

    @Test
    void manualRowsStayCreatorScopedAndMissingAmountsAreCounted() {
        insert("manual", "T1", "MANUAL", null);
        insert("notmine", "T1", "NOTMINE", null);
        jdbc.update("UPDATE order_logistics_cost SET created_by='ANOTHER' WHERE id='notmine'");
        jdbc.update(
                "UPDATE order_logistics_cost SET amount=NULL,status_code='ACTIVE' WHERE"
                        + " id='manual'");
        var result =
                store.entries(
                        "T1",
                        "LOGISTICS",
                        new LogisticsCostQuery(
                                0, 20, null, null, null, null, null, null, null, null, null, null,
                                null, null, null),
                        "cost:read");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.missingAmountCount()).isEqualTo(1);
        assertThat(result.registeredAmount()).isEqualByComparingTo("100");
    }

    @Test
    void deliveryModeFiltersTotalsAndIncludesLegacyPlatformRecords() {
        jdbc.execute("ALTER TABLE order_logistics_cost ADD delivery_mode_code VARCHAR(50)");
        insert("platform", "T1", "PLATFORM", 7L);
        insert("express", "T1", "EXPRESS", 7L);
        jdbc.update("UPDATE order_logistics_cost SET source_warehouse_id=warehouse_id");
        jdbc.update("UPDATE order_logistics_cost SET delivery_mode_code='SELF' WHERE id IN ('good','hidden')");
        jdbc.update("UPDATE order_logistics_cost SET delivery_mode_code='THIRD_PARTY' WHERE id='express'");
        jdbc.update("INSERT INTO order_logistics_import_order(id,tenant_id,cost_id,source_channel,source_json)"
                + " VALUES('source','T1','platform','API','{}')");

        var self = JSON.readValue("{\"begin\":0,\"step\":1,\"deliveryModeCode\":\"SELF\"}", LogisticsCostQuery.class);
        var selfPage = store.entries("T1", "LOGISTICS", self, "cost:read");
        assertThat(selfPage.items()).extracting(row -> row.id()).containsExactly("good");
        assertThat(selfPage.total()).isEqualTo(1);
        assertThat(selfPage.registeredAmount()).isEqualByComparingTo("100.00");

        var thirdParty = JSON.readValue("{\"begin\":0,\"step\":1,\"deliveryModeCode\":\"THIRD_PARTY\"}", LogisticsCostQuery.class);
        var thirdPartyPage = store.entries("T1", "LOGISTICS", thirdParty, "cost:read");
        assertThat(thirdPartyPage.items()).hasSize(1);
        assertThat(thirdPartyPage.total()).isEqualTo(2);
        assertThat(thirdPartyPage.registeredAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void keywordSearchCoversDocumentsAndPartiesWithinScope() {
        assertThat(countObject("SENDER-ONLY")).isEqualTo(1);
        assertThat(countObject("SRC-A")).isEqualTo(1);
        assertThat(countObject("%")).isZero();
        jdbc.update(
                "UPDATE order_logistics_cost SET sources_json=? WHERE id='good'",
                "[{\"documentNo\":\"SRC-A\",\"receiverName\":\"中心仓100%\"}]");
        assertThat(countObject("中心仓100%")).isEqualTo(1);
        jdbc.update(
                "UPDATE order_logistics_cost SET data_json=? WHERE id='good'",
                "{\"logistics\":{\"senderName\":\"订货宝供应商\"}}");
        assertThat(countObject("订货宝供应商")).isEqualTo(1);
        assertThat(countObject("不存在")).isZero();
    }

    long countObject(String name) {
        return store.entries(
                        "T1",
                        "LOGISTICS",
                        new LogisticsCostQuery(
                                0, 20, null, null, null, null, null, null, null, null, null, null,
                                null, null, null, name),
                        "cost:read")
                .total();
    }
}
