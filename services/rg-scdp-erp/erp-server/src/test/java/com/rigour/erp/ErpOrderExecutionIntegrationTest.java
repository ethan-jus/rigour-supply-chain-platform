package com.rigour.erp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.application.port.out.OrderExecutionClient;
import com.rigour.erp.application.service.inventory.ErpOrderExecutionService;
import com.rigour.erp.infrastructure.persistence.repository.ErpWarehouseDataScope;
import com.rigour.order.api.v1.model.FulfillmentExecutionView;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 验证真实库存、出库单、流水、回执共事务，重试不会重复扣库。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ErpOrderExecutionIntegrationTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("rigour_erp")
                    .withUsername("rigour_erp_test")
                    .withPassword("rigour_erp_test");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.flyway.enabled", () -> true);
        r.add("spring.flyway.url", MYSQL::getJdbcUrl);
        r.add("spring.flyway.user", MYSQL::getUsername);
        r.add("spring.flyway.password", MYSQL::getPassword);
    }

    @MockitoBean SupplyAuthorizationClient authorization;
    @Autowired ErpWarehouseDataScope scopes;
    @Autowired JdbcTemplate jdbc;
    @Autowired ErpOrderExecutionService service;
    @MockitoBean OrderExecutionClient order;
    String tenant;
    long warehouse, product, variant;
    CallerIdentity actor;

    @BeforeEach
    void setup() {
        tenant = UUID.randomUUID().toString();
        UUID user = UUID.randomUUID();
        actor =
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.fromString(tenant),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:outbound:confirm"));
        TestAuthorizationContext.set(actor);
        when(authorization.authorization(any(), anyString()))
                .thenAnswer(
                        i ->
                                policy(
                                        "PREPARING",
                                        i.getArgument(1),
                                        List.of(),
                                        new Limit("ALL", List.of())));
        jdbc.update(
                "INSERT INTO"
                    + " erp_inventory_warehouse(tenant_id,warehouse_code,warehouse_name,status_code)"
                    + " VALUES(?,'HZ','杭州仓','ACTIVE')",
                tenant);
        warehouse =
                jdbc.queryForObject(
                        "SELECT id FROM erp_inventory_warehouse WHERE tenant_id=?",
                        Long.class,
                        tenant);
        jdbc.update(
                "INSERT INTO"
                    + " erp_product(tenant_id,product_code,product_name,unit_code,submit_status_code)"
                    + " VALUES(?,'P1','商品','PCS','SUBMITTED')",
                tenant);
        product =
                jdbc.queryForObject(
                        "SELECT id FROM erp_product WHERE tenant_id=?", Long.class, tenant);
        jdbc.update(
                "INSERT INTO"
                    + " erp_product_variant(tenant_id,product_id,variant_code,unit_code,sale_price)"
                    + " VALUES(?,?,'V1','PCS',5)",
                tenant,
                product);
        variant =
                jdbc.queryForObject(
                        "SELECT id FROM erp_product_variant WHERE tenant_id=?", Long.class, tenant);
        jdbc.update(
                "INSERT INTO"
                    + " erp_stock_balance(tenant_id,warehouse_id,product_id,product_variant_id,available_quantity)"
                    + " VALUES(?,?,?,?,10)",
                tenant,
                warehouse,
                product,
                variant);
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Autowired
    private com.rigour.erp.application.port.out.AnalyticsSourceSnapshotStore sourceSnapshot;

    @Test
    void sourceSnapshotContractUsesOnlyKnownTenantDatasets() {
        String emptyTenant = java.util.UUID.randomUUID().toString();
        for (String dataset :
                java.util.List.of(
                        "ERP_INVENTORY_WAREHOUSE",
                        "ERP_PROCUREMENT_ORDER",
                        "ERP_PROCUREMENT_ORDER_LINE",
                        "ERP_PRODUCT",
                        "ERP_PRODUCT_BRAND",
                        "ERP_PRODUCT_CATEGORY",
                        "ERP_PRODUCT_VARIANT",
                        "ERP_STOCK_BALANCE",
                        "ERP_STOCK_OUT_ORDER",
                        "ERP_STOCK_OUT_ORDER_LINE")) {
            var page = sourceSnapshot.page(emptyTenant, dataset, "");
            org.assertj.core.api.Assertions.assertThat(page.items()).isEmpty();
            org.assertj.core.api.Assertions.assertThat(page.version())
                    .isEqualTo(sourceSnapshot.version(emptyTenant, dataset));
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sourceSnapshot.page(emptyTenant, "iam_user", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionAndRetryDeductOnceAndReadOnlyRecoveryCannotExecute() {
        var c = command(2);
        when(order.claim(any(), eq(c.executionId()))).thenReturn(c);
        var first = service.execute(c.executionId());
        var second = service.execute(c.executionId());
        assertThat(second.stockOutId()).isEqualTo(first.stockOutId());
        assertThat(balance()).isEqualByComparingTo("8");
        assertThat(count("erp_stock_out_order")).isEqualTo(1);
        assertThat(count("erp_stock_flow")).isEqualTo(1);
        assertThat(count("erp_order_execution_receipt")).isEqualTo(1);
        var recovery =
                new CallerIdentity(
                        "SERVICE",
                        UUID.randomUUID(),
                        UUID.fromString(tenant),
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("erp:execution:receipt-read"));
        TestAuthorizationContext.set(recovery);
        assertThat(service.receipt(c.executionId()).stockOutId()).isEqualTo(first.stockOutId());
        assertThatThrownBy(() -> service.execute(UUID.randomUUID().toString()))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(balance()).isEqualByComparingTo("8");
    }

    @Test
    void insufficientStockRollsBackReceiptHeaderAndBalance() {
        var c = command(20);
        when(order.claim(any(), eq(c.executionId()))).thenReturn(c);
        assertThatThrownBy(() -> service.execute(c.executionId())).hasMessageContaining("库存不足");
        assertThat(balance()).isEqualByComparingTo("10");
        assertThat(count("erp_stock_out_order")).isZero();
        assertThat(count("erp_stock_flow")).isZero();
        assertThat(count("erp_order_execution_receipt")).isZero();
    }

    @Test
    void changedOrderAuthorizationPreventsAnyInventoryWrite() {
        var c = command(2);
        when(order.claim(any(), eq(c.executionId())))
                .thenThrow(new AuthorizationDeniedException("order:outbound:confirm"));
        assertThatThrownBy(() -> service.execute(c.executionId()))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(balance()).isEqualByComparingTo("10");
        assertThat(count("erp_order_execution_receipt")).isZero();
    }

    @Test
    void warehouseScopesFilterBeforeCountingAndNeverCombineDifferentRolesForTransfer() {
        jdbc.update(
                "INSERT INTO"
                    + " erp_inventory_warehouse(tenant_id,warehouse_code,warehouse_name,status_code)"
                    + " VALUES(?,'NB','宁波仓','ACTIVE')",
                tenant);
        long other =
                jdbc.queryForObject(
                        "SELECT id FROM erp_inventory_warehouse WHERE tenant_id=? AND"
                            + " warehouse_code='NB'",
                        Long.class,
                        tenant);
        var all = new Limit("ALL", List.of());
        var none = new Limit("NONE", List.of());
        var hz =
                new Clause(
                        UUID.randomUUID(),
                        "INVENTORY",
                        "WAREHOUSE",
                        none,
                        none,
                        new Limit("SPECIFIED", List.of("" + warehouse)),
                        false);
        var nb =
                new Clause(
                        UUID.randomUUID(),
                        "INVENTORY",
                        "WAREHOUSE",
                        none,
                        none,
                        new Limit("SPECIFIED", List.of("" + other)),
                        false);
        when(authorization.authorization(any(), anyString()))
                .thenAnswer(i -> policy("ACTIVE", i.getArgument(1), List.of(hz), all));
        var p = scopes.predicate("erp:warehouse:read", "id");
        var args = new ArrayList<Object>(List.of(tenant));
        args.addAll(p.args());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM erp_inventory_warehouse WHERE tenant_id=? AND"
                                    + " "
                                        + p.sql(),
                                Integer.class,
                                args.toArray()))
                .isEqualTo(1);
        assertThat(
                        scopes.visibleDocument(
                                tenant,
                                other,
                                "erp_inventory_warehouse",
                                "erp:warehouse:read",
                                "id"))
                .isFalse();
        when(authorization.authorization(any(), anyString()))
                .thenAnswer(i -> policy("ACTIVE", i.getArgument(1), List.of(hz, nb), all));
        assertThatThrownBy(
                        () -> scopes.requirePair(tenant, warehouse, other, "erp:transfer:create"))
                .isInstanceOf(AuthorizationDeniedException.class);
        var both =
                new Clause(
                        UUID.randomUUID(),
                        "INVENTORY",
                        "WAREHOUSE",
                        none,
                        none,
                        new Limit("SPECIFIED", List.of("" + warehouse, "" + other)),
                        false);
        when(authorization.authorization(any(), anyString()))
                .thenAnswer(i -> policy("ACTIVE", i.getArgument(1), List.of(both), all));
        assertThatCode(() -> scopes.requirePair(tenant, warehouse, other, "erp:transfer:create"))
                .doesNotThrowAnyException();
        when(authorization.authorization(any(), anyString()))
                .thenAnswer(
                        i ->
                                policy(
                                        "ACTIVE",
                                        i.getArgument(1),
                                        List.of(both),
                                        new Limit("SPECIFIED", List.of("" + warehouse))));
        assertThatThrownBy(
                        () -> scopes.requirePair(tenant, warehouse, other, "erp:transfer:create"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    private SupplyAuthorizationView policy(
            String mode, String action, List<Clause> clauses, Limit cap) {
        return new SupplyAuthorizationView(
                mode,
                UUID.fromString(tenant),
                actor.userId(),
                "E1",
                1,
                1,
                1,
                1,
                Set.of(action),
                action,
                true,
                clauses,
                new Limit("ALL", List.of()),
                cap);
    }

    private FulfillmentExecutionView command(int quantity) {
        return new FulfillmentExecutionView(
                tenant,
                UUID.randomUUID().toString(),
                1,
                "DD-1",
                2,
                warehouse,
                1L,
                "客户",
                Instant.parse("2026-09-15T01:00:00Z"),
                List.of(
                        new FulfillmentExecutionView.Line(
                                1,
                                product,
                                variant,
                                "P1",
                                "V1",
                                "商品",
                                "PCS",
                                BigDecimal.valueOf(quantity),
                                null)),
                null,
                "fixed-request-hash");
    }

    private BigDecimal balance() {
        return jdbc.queryForObject(
                "SELECT available_quantity FROM erp_stock_balance WHERE tenant_id=?",
                BigDecimal.class,
                tenant);
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant);
    }
    @org.springframework.beans.factory.annotation.Autowired private com.rigour.erp.application.port.out.SupplyReadinessStore supplyReadiness;
    @org.junit.jupiter.api.Test
    void readinessChecksRunAgainstTheMigratedTenantSchema() {
      var report=supplyReadiness.inspect(java.util.UUID.randomUUID().toString());
      org.assertj.core.api.Assertions.assertThat(report.contractVersion()).isEqualTo(1);
      org.assertj.core.api.Assertions.assertThat(report.version()).isNotBlank();
      org.assertj.core.api.Assertions.assertThat(report.checks()).allMatch(c->c.count()==0);
    }
    @Autowired com.rigour.erp.application.port.out.ErpProcurementOrderStore procurements;
    @Test
    void procurementReadsAndMutationsStayInsideTheirWarehouseScope() {
      jdbc.update("INSERT INTO erp_inventory_warehouse(tenant_id,warehouse_code,warehouse_name,status_code) VALUES(?,'NB','宁波仓','ACTIVE')",tenant);
      long other=jdbc.queryForObject("SELECT id FROM erp_inventory_warehouse WHERE tenant_id=? AND warehouse_code='NB'",Long.class,tenant);
      jdbc.update("INSERT INTO erp_supplier_profile(tenant_id,supplier_code,supplier_name,status_code) VALUES(?,'SUP','测试供应商','ACTIVE')",tenant);
      long supplier=jdbc.queryForObject("SELECT id FROM erp_supplier_profile WHERE tenant_id=?",Long.class,tenant);
      jdbc.update("INSERT INTO erp_procurement_order(tenant_id,procurement_no,supplier_id,target_warehouse_id,status_code) VALUES(?,'HZ-PO',?,?,'DRAFT'),(?,'NB-PO',?,?,'DRAFT')",tenant,supplier,warehouse,tenant,supplier,other);
      long hidden=jdbc.queryForObject("SELECT id FROM erp_procurement_order WHERE tenant_id=? AND procurement_no='NB-PO'",Long.class,tenant);
      var rule=new Clause(UUID.randomUUID(),"INVENTORY","WAREHOUSE",new Limit("NONE",List.of()),new Limit("NONE",List.of()),new Limit("SPECIFIED",List.of(String.valueOf(warehouse))),true);
      when(authorization.authorization(any(),anyString())).thenAnswer(i->policy("ACTIVE",i.getArgument(1),List.of(rule),new Limit("ALL",List.of())));
      var criteria=new com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderSearchCriteria(null,null,null,null,null,null);
      assertThat(procurements.procurementOrders(tenant,0,1,criteria).total()).isEqualTo(1);
      assertThat(procurements.procurementOrders(tenant,0,1,criteria).items()).hasSize(1);
      assertThat(procurements.procurementOrder(tenant,hidden)).isEmpty();
      assertThatThrownBy(()->procurements.delete(tenant,hidden,1,actor.principalId().toString())).isInstanceOf(AuthorizationDeniedException.class);
      assertThat(jdbc.queryForObject("SELECT deleted FROM erp_procurement_order WHERE id=?",Integer.class,hidden)).isZero();
    }
}
