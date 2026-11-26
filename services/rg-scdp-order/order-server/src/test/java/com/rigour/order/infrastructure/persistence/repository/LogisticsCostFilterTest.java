package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.api.v1.model.LogisticsCostQuery;
import com.rigour.order.api.v1.model.ShipmentTransportModels.Source;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.order.application.port.out.PlatformTransportStore;
import com.rigour.order.application.service.cost.OrderLogisticsCostService;
import com.rigour.order.application.service.cost.TransportRecordService;
import com.rigour.order.infrastructure.persistence.mapper.*;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
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
                        + " order_logistics_transport_link, order_transport_shipment_source, order_sales_order,"
                        + " order_sales_shipment, order_logistics_cost_attachment");
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
                        + " VARCHAR(50),cost_id VARCHAR(50),carrier_order_no VARCHAR(80),source_json LONGTEXT,latest_source_json"
                        + " LONGTEXT,source_channel VARCHAR(16) DEFAULT 'API',business_json JSON)"
                        + " DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        jdbc.execute(
                "CREATE TABLE order_transport_shipment_source(tenant_id VARCHAR(50),cost_id"
                        + " VARCHAR(50),id VARCHAR(50),connector_id VARCHAR(36),source_shipment_no VARCHAR(128),"
                        + " updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + " latest_json LONGTEXT,platform_code VARCHAR(32) DEFAULT 'DHB',"
                        + " sales_order_id BIGINT,issue VARCHAR(500),difference_pending TINYINT DEFAULT 0)"
                        + " DEFAULT CHARSET=utf8mb4"
                        + " COLLATE=utf8mb4_general_ci");
        jdbc.execute("""
                CREATE TABLE order_sales_order(id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),
                    order_no VARCHAR(50),deleted INT,order_status_code VARCHAR(32))
                DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE order_sales_shipment(
                    tenant_id VARCHAR(64),connector_id VARCHAR(36),source_system_code VARCHAR(32),
                    source_document_no VARCHAR(128),sales_order_no_snapshot VARCHAR(50),
                    shipment_status_code VARCHAR(64),logistics_company VARCHAR(120),
                    tracking_no VARCHAR(120),source_payload_json JSON,deleted INT DEFAULT 0,
                    UNIQUE KEY shipment_origin(tenant_id,connector_id,source_system_code,source_document_no))
                DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE order_logistics_cost_attachment(
                    tenant_id VARCHAR(50),entry_id VARCHAR(50),deleted INT DEFAULT 0)
                DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """);
        jdbc.execute("""
                CREATE TABLE order_logistics_transport_link(tenant_id VARCHAR(50),source_id VARCHAR(50))
                DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """);
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
                "INSERT INTO order_transport_shipment_source(tenant_id,cost_id,latest_json)"
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

        jdbc.update("INSERT INTO order_transport_shipment_source(tenant_id,cost_id,latest_json)"
                + " VALUES('T2','platform','{}')");
        String salesFilters =
                "\"orderPlacerEmployeeCode\":\"E1\",\"orderPlacerDepartment\":\"武汉销售部\","
                        + "\"businessOrderNo\":\"HLL-SALES-1\"";
        assertThat(transportIds(salesFilters)).isEmpty();

        jdbc.update("INSERT INTO order_transport_shipment_source(tenant_id,cost_id,latest_json)"
                + " VALUES('T1','platform','{}')");
        assertThat(transportIds(salesFilters)).containsExactly("platform");
        assertThat(transportIds(
                        "\"orderPlacerEmployeeCode\":\"E4\",\"orderPlacerDepartment\":\"本系统运营部\","
                                + "\"businessOrderNo\":\"HLL-SALES-1\""))
                .containsExactly("platform");
        assertThat(transportIds(
                        "\"orderPlacerEmployeeCode\":\"E1\",\"orderPlacerDepartment\":\"本系统运营部\""))
                .isEmpty();
    }

    @Test
    void verifiedShipmentSalesOrdersAreSearchableAndCountedOnceWithoutAStoredCostShare() {
        jdbc.update("UPDATE order_logistics_cost SET sources_json='[]',type_code='DELIVERY'");
        jdbc.update("""
                INSERT INTO order_logistics_import_order(id,tenant_id,cost_id,business_json)
                VALUES('platform','T1','good',JSON_OBJECT('orderCodes',JSON_ARRAY('EXISTING-COST-ORDER')))
                """);
        jdbc.update("""
                INSERT INTO order_sales_order VALUES
                    (1,'T1','DHB-A_1',0,'SUBMITTED'),(2,'T1','DHB-B',0,'COMPLETED'),
                    (3,'T1','PENDING-LINK',0,'SUBMITTED'),(4,'T1','CANCELLED-SHIPMENT',0,'SUBMITTED'),
                    (5,'T1','CANCELLED-SALE',0,'CANCELLED'),(6,'T2','OTHER-TENANT',0,'COMPLETED'),
                    (7,'T1','DELETED-SALE',1,'SUBMITTED'),(8,'T1','REVIEW-LINK',0,'SUBMITTED')
                """);
        insertShipment("T1", "good", 1, "receivedin", null, false);
        insertShipment("T1", "good", 1, "received", null, false);
        insertShipment("T1", "good", 2, "received", null, false);
        insertShipment("T1", "good", 3, "received", null, true);
        insertShipment("T1", "good", 4, "cancelled", null, false);
        insertShipment("T1", "good", 5, "received", null, false);
        insertShipment("T1", "good", 6, "received", null, false);
        insertShipment("T1", "good", 7, "received", null, false);
        insertShipment("T1", "good", 8, "received", "需核对", false);
        insertShipment("T2", "other", 6, "received", null, false);
        insertShipment("T1", "hidden", 1, "received", null, false);

        for (String orderNo : List.of("DHB-A_1", "DHB-B")) {
            assertThat(transportIds("\"businessOrderNo\":\"" + orderNo + "\""))
                    .containsExactly("good");
            assertThat(store.linkedSales("T1", orderNo, "cost:read"))
                    .extracting(row -> row.id()).containsExactly("good");
        }
        assertThat(transportIds("\"businessOrderNo\":\"EXISTING-COST-ORDER\""))
                .containsExactly("good");
        var ignored = List.of("PENDING-LINK", "CANCELLED-SHIPMENT", "CANCELLED-SALE",
                "OTHER-TENANT", "DELETED-SALE", "REVIEW-LINK", "DHB-Ax1");
        for (String orderNo : ignored) {
            assertThat(transportIds("\"businessOrderNo\":\"" + orderNo + "\"")).isEmpty();
            assertThat(store.linkedSales("T1", orderNo, "cost:read")).isEmpty();
        }
        var allOrderNos = new ArrayList<>(ignored);
        allOrderNos.addAll(List.of("DHB-A_1", "DHB-B"));
        assertThat(store.sourceUsage("T1", allOrderNos, "cost:read"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("DHB-A_1", 1, "DHB-B", 1));
        assertThat(transportIds("\"relationState\":\"LINKED\"")).containsExactly("good");
        assertThat(transportIds("\"relationState\":\"UNLINKED\"")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT sources_json FROM order_logistics_cost WHERE id='good'", String.class))
                .isEqualTo("[]");
    }

    @Test
    void storedModernAndLegacyBusinessOrderNumbersRemainSearchable() {
        jdbc.update("""
                UPDATE order_logistics_cost SET sources_json=JSON_ARRAY(
                    JSON_OBJECT('orderCode','MODERN-ORDER','orderTypeCode','SALES_ORDER'),
                    JSON_OBJECT('documentNo','LEGACY-ORDER','sourceType','SALES_ORDER'))
                WHERE id='good'
                """);
        assertThat(transportIds("\"businessOrderNo\":\"MODERN-ORDER\"")).containsExactly("good");
        assertThat(transportIds("\"businessOrderNo\":\"LEGACY-ORDER\"")).containsExactly("good");
    }

    @Test
    void snapshotOnlySalesOrderIsListedAndExportedWithoutBecomingACanonicalLink() {
        UUID tenant = UUID.randomUUID();
        String tenantId = tenant.toString();
        String connector = UUID.randomUUID().toString();
        String waybill = "HLL-WAY-001";
        String orderNo = "DHB-ORDER_123";
        insert("snapshot", tenantId, "SNAPSHOT", 7L);
        insert("invalid-snapshot", tenantId, "INVALID", 7L);
        jdbc.update("""
                UPDATE order_logistics_cost
                SET sources_json='[]',type_code='DELIVERY',source_system='HUOLALA',
                    source_warehouse_id=warehouse_id
                WHERE tenant_id=?
                """, tenantId);
        jdbc.update("""
                INSERT INTO order_logistics_import_order(id,tenant_id,cost_id,carrier_order_no,source_json)
                VALUES('platform-reference',?,'snapshot',?,'{}'),
                    ('platform-invalid',?,'invalid-snapshot','HLL-WAY-002','{}')
                """, tenantId, waybill, tenantId);
        insertShipmentReference(tenantId, connector, "snapshot", "FH-1", orderNo,
                waybill, waybill, "SIGNED");
        insertShipmentReference(tenantId, connector, "invalid-snapshot", "FH-2", "DHB-STALE",
                "HLL-WAY-002", "HLL-WAY-002-NEW", "SIGNED");
        insertShipmentReference(tenantId, connector, "invalid-snapshot", "FH-3", "DHB-CANCELLED",
                "HLL-WAY-002", "HLL-WAY-002", "CANCELLED");

        var refs = mock(OrderLogisticsReferences.class);
        var platform = mock(PlatformTransportStore.class);
        var transport = new TransportRecordService(
                new OrderLogisticsCostService(store, refs), null, refs, platform, null);
        ReflectionTestUtils.invokeMethod(
                transport, "shipmentStore", new JdbcShipmentTransportStore(jdbc, JSON, platform, store));
        UUID actor = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", actor, tenant, actor, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(),
                Set.of("order:logistics-cost:read", "order:logistics-cost:export")));
        try {
            for (String term : List.of(orderNo, "ORDER_12")) {
                var query = transportQuery(Map.of("businessOrderNo", term));
                for (boolean export : List.of(false, true)) {
                    var page = transport.page(query, export);
                    assertThat(page.page().total()).isOne();
                    assertThat(page.page().items()).extracting(row -> row.id()).containsExactly("snapshot");
                    assertThat(page.shipmentRecords().get("snapshot"))
                            .allMatch(source -> source.salesOrderNo() == null)
                            .extracting(Source::sourceSalesOrderNo).containsExactly(orderNo);
                }
            }
            for (String ignored : List.of("DHB-STALE", "DHB-CANCELLED", "ORDERX123")) {
                var query = transportQuery(Map.of("businessOrderNo", ignored));
                assertThat(transport.page(query, false).page().total()).isZero();
                assertThat(transport.page(query, true).page().total()).isZero();
            }
            var linked = transport.page(transportQuery(Map.of("relationState", "LINKED")), false);
            assertThat(linked.page().items()).extracting(row -> row.id()).containsExactly("snapshot");
            var unlinked = transport.page(transportQuery(Map.of("relationState", "UNLINKED")), false);
            assertThat(unlinked.page().items()).extracting(row -> row.id()).containsExactly("invalid-snapshot");
            assertThat(unlinked.shipmentRecords().get("invalid-snapshot"))
                    .allMatch(source -> source.sourceSalesOrderNo() == null);
            var selected = transport.selected(List.of("snapshot"));
            assertThat(selected.getFirst().shipments()).extracting(Source::sourceSalesOrderNo)
                    .containsExactly(orderNo);
            assertThat(selected.getFirst().orderAllocations()).isEmpty();
        } finally {
            TestAuthorizationContext.clear();
        }

        assertThat(store.sourceUsage(tenantId, List.of(orderNo), "cost:read")).isEmpty();
        assertThat(store.linkedSales(tenantId, orderNo, "cost:read")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT sources_json FROM order_logistics_cost WHERE id='snapshot'",
                String.class)).isEqualTo("[]");
    }

    private void insertShipmentReference(String tenant, String connector, String cost, String shipment,
            String order, String associatedTracking, String latestTracking, String shipmentStatus) {
        jdbc.update("""
                INSERT INTO order_transport_shipment_source(
                    id,tenant_id,connector_id,cost_id,source_shipment_no,latest_json)
                VALUES(?,?,?,?,?,JSON_OBJECT('sourceStatus','received','connectorId',?,
                    'shipmentNo',?,'trackingNumbers',JSON_ARRAY(?)))
                """, shipment, tenant, connector, cost, shipment, connector, shipment,
                associatedTracking);
        jdbc.update("""
                INSERT INTO order_sales_shipment(tenant_id,connector_id,source_system_code,
                    source_document_no,sales_order_no_snapshot,shipment_status_code,
                    logistics_company,tracking_no,source_payload_json)
                VALUES(?,?,'DINGHUOBAO',?,?,?,'货拉拉',?,
                    JSON_OBJECT('detail',JSON_OBJECT('type_id',10,'ships_num',?,
                        'orders_num',?,'express_num',?)))
                """, tenant, connector, shipment, order, shipmentStatus, latestTracking,
                shipment, order, latestTracking);
    }

    private static LogisticsCostQuery transportQuery(Map<String, Object> filter) {
        var fields = new HashMap<>(filter);
        fields.put("begin", 0);
        fields.put("step", 20);
        return JSON.convertValue(fields, LogisticsCostQuery.class);
    }

    private void insertShipment(String tenant, String cost, long sale, String status, String issue, boolean difference) {
        jdbc.update("""
                INSERT INTO order_transport_shipment_source(tenant_id,cost_id,sales_order_id,latest_json,issue,difference_pending)
                VALUES(?,?,?,JSON_OBJECT('sourceStatus',?),?,?)
                """, tenant, cost, sale, status, issue, difference);
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
