package com.rigour.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.order.application.port.out.OrderAttributionClient;
import com.rigour.order.infrastructure.persistence.repository.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Instant;
import java.util.*;

/** 使用真实 MySQL 迁移验证归属冻结、失败回滚、历史部门路径及多角色条件组合。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OrderAttributionAndScopeIntegrationTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("rigour_order")
                    .withUsername("rigour_order_test")
                    .withPassword("rigour_order_test");

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

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired OrderAttributionWriter writer;
    @Autowired JdbcOrderFulfillmentStore fulfillment;
    @Autowired OrderDataScope scopes;
    @Autowired com.rigour.order.application.port.out.OrderSalesPaymentRecordStore payments;
    @Autowired com.rigour.order.application.port.out.OrderRegisterStore register;
    @Autowired com.rigour.order.application.port.out.OrderParameterStore parameterStore;
    @Autowired com.rigour.order.application.service.sales.OrderSalesOrderService sales;
    @MockitoBean OrderAttributionClient customer;
    @MockitoBean com.rigour.order.application.port.out.HrEmployeeDisplayClient employeeDisplay;
    @Autowired com.rigour.order.application.port.out.OrderAttributionReviewStore attributionReview;
    @MockitoBean com.rigour.order.application.port.out.OrderRepairUnitDictionary units;
    @MockitoBean SupplyAuthorizationClient iam;
    String tenant;
    CallerIdentity actor;
    TransactionTemplate tx;

    @BeforeEach
    void setup() {
        when(units.validUnits(anyString())).thenReturn(Set.of("BOX", "PIECE", "SET"));
        when(employeeDisplay.resolve(any(), anySet()))
                .thenAnswer(
                        inv ->
                                ((Set<String>) inv.getArgument(1))
                                        .stream()
                                                .map(
                                                        code ->
                                                                new com.rigour.order.application
                                                                        .port.out
                                                                        .HrEmployeeDisplayClient
                                                                        .EmployeeDisplay(
                                                                        code, "当前姓名", "INACTIVE", "销售部"))
                                                .toList());
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
                        Set.of("TENANT_SUPER_ADMIN"),
                        Set.of("*"));
        TestAuthorizationContext.set(actor);
        tx = new TransactionTemplate(manager);
    }

    @AfterEach
    void cleanup() {
        TestAuthorizationContext.clear();
    }

    @Autowired
    com.rigour.order.application.port.out.OrderAuthorityProjectionStore authorityProjection;

    @Autowired
    private com.rigour.order.application.port.out.AnalyticsSourceSnapshotStore sourceSnapshot;

    @Test
    void sourceSnapshotContractUsesOnlyKnownTenantDatasets() {
        String emptyTenant = java.util.UUID.randomUUID().toString();
        for (String dataset :
                java.util.List.of(
                        "ORDER_PAYMENT_RECORD",
                        "ORDER_REFUND_RECORD",
                        "ORDER_SALES_ORDER",
                        "ORDER_SALES_ORDER_LINE")) {
            var page = sourceSnapshot.page(emptyTenant, dataset, "");
            assertThat(page.items()).isEmpty();
            assertThat(page.version()).isEqualTo(sourceSnapshot.version(emptyTenant, dataset));
        }
        assertThatThrownBy(() -> sourceSnapshot.page(emptyTenant, "iam_user", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void analyticsAuthorityUsesFrozenSnapshotAndMarksUnmappedOrderForReview() {
        long first = order("snapshot", 1L), unmapped = order("unmapped", 2L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(
                        attribution(
                                "v1",
                                "EMP-A",
                                10,
                                List.of(1L, 10L),
                                "HZ",
                                List.of("ZJ", "HZ"),
                                true));
        tx.executeWithoutResult(s -> writer.prepare(tenant, first, true, false));
        var page = authorityProjection.page(tenant, 0, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().get("employeeCode")).isEqualTo("EMP-A");
        assertThat(page.items().getFirst().get("attributionState")).isEqualTo("FROZEN");
        var next = authorityProjection.page(tenant, first, 1);
        assertThat(next.items().getFirst().get("id")).isEqualTo(unmapped);
        assertThat(next.items().getFirst().get("attributionState")).isEqualTo("REVIEW");
        assertThat(next.items().getFirst().get("employeeCode")).isNull();
    }

    @Autowired com.rigour.order.application.port.out.OrderSalesOrderStore orders;

    @Test
    void ownDraftDoesNotGrantAnotherSalespersonsCustomerAndSubmitRechecksTransferredOwner() {
        long draft = order("draft-scope", 1L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(attribution("a", "EMP-A", 10, List.of(10L), "HZ", List.of("HZ"), true));
        tx.executeWithoutResult(s -> writer.prepare(tenant, draft, false, false));
        when(iam.authorization(any(), any()))
                .thenAnswer(
                        call ->
                                policy(
                                        call.getArgument(1),
                                        List.of(
                                                new Clause(
                                                        UUID.randomUUID(),
                                                        "ORDER",
                                                        "SELF",
                                                        none(),
                                                        all(),
                                                        none(),
                                                        false)),
                                        all(),
                                        all()));
        int revision =
                jdbc.queryForObject(
                        "SELECT revision FROM order_sales_order WHERE tenant_id=? AND id=?",
                        Integer.class,
                        tenant,
                        draft);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(attribution("b", "EMP-B", 20, List.of(20L), "HZ", List.of("HZ"), true));
        assertThatThrownBy(() -> orders.submit(tenant, draft, revision, actor.userId().toString()))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM order_attribution_snapshot WHERE tenant_id=? AND"
                                        + " order_id=?",
                                String.class,
                                tenant,
                                draft))
                .isEqualTo("DRAFT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT owner_employee_code FROM order_sales_order WHERE"
                                        + " tenant_id=? AND id=?",
                                String.class,
                                tenant,
                                draft))
                .isEqualTo("EMP-A");
        tx.executeWithoutResult(s -> writer.prepare(tenant, draft, false, false));
        assertThatCode(() -> scopes.requireOrder(tenant, draft, "order:read"))
                .doesNotThrowAnyException();
        assertThatCode(() -> scopes.requireOrder(tenant, draft, "order:update"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> scopes.requireAttributedOrder(tenant, draft, "order:update"))
                .isInstanceOf(AuthorizationDeniedException.class);
        for (String action : List.of("order:create", "order:submit", "order:warehouse:select"))
            assertThatThrownBy(() -> scopes.requireOrder(tenant, draft, action))
                    .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void relatedDocumentsUseOriginalOrderBeforePaginationAndRejectCrossScopeMutations() {
        long mine = order("mine", 1L), other = order("other", 2L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(attribution("m", "EMP-A", 10, List.of(10L), "HZ", List.of("HZ"), true));
        when(customer.resolve(any(), eq(2L)))
                .thenReturn(attribution("o", "EMP-B", 20, List.of(20L), "NB", List.of("NB"), true));
        tx.executeWithoutResult(
                s -> {
                    writer.prepare(tenant, mine, true, false);
                    writer.prepare(tenant, other, true, false);
                });
        for (long order : List.of(mine, other)) {
            jdbc.update(
                    "INSERT INTO"
                        + " order_payment_record(tenant_id,payment_no,order_id,payment_time,paid_amount)"
                        + " VALUES(?,?,?,UTC_TIMESTAMP(6),10)",
                    tenant,
                    "PAY-" + order,
                    order);
            jdbc.update(
                    "INSERT INTO"
                        + " order_refund_record(tenant_id,refund_no,order_id,refund_time,refund_amount)"
                        + " VALUES(?,?,?,UTC_TIMESTAMP(6),2)",
                    tenant,
                    "REF-" + order,
                    order);
            jdbc.update(
                    "INSERT INTO order_sales_shipment(tenant_id,shipment_no,sales_order_id)"
                            + " VALUES(?,?,?)",
                    tenant,
                    "SHIP-" + order,
                    order);
            jdbc.update(
                    "INSERT INTO"
                        + " order_fund_document(tenant_id,document_no,related_order_id,direction_code,occurred_time,amount)"
                        + " VALUES(?,?,?,'RECEIPT',UTC_TIMESTAMP(6),10)",
                    tenant,
                    "FUND-" + order,
                    order);
        }
        jdbc.update(
                "INSERT INTO"
                    + " order_fund_document(tenant_id,document_no,direction_code,occurred_time,amount)"
                    + " VALUES(?,'NONORDER','RECEIPT',UTC_TIMESTAMP(6),10)",
                tenant);
        for (var entry :
                Map.of(
                                "payment",
                                "order_payment_record",
                                "refund",
                                "order_refund_record",
                                "shipment",
                                "order_sales_shipment",
                                "fund",
                                "order_fund_document")
                        .entrySet()) {
            String action = "order:" + entry.getKey() + ":read";
            when(iam.authorization(any(), eq(action)))
                    .thenReturn(
                            policy(
                                    action,
                                    List.of(
                                            new Clause(
                                                    UUID.randomUUID(),
                                                    "ORDER",
                                                    "SELF",
                                                    none(),
                                                    all(),
                                                    none(),
                                                    false)),
                                    all(),
                                    all()));
            var p = scopes.relatedPredicate(action, entry.getValue());
            var args = new ArrayList<Object>(List.of(tenant));
            args.addAll(p.args());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM "
                                            + entry.getValue()
                                            + " WHERE tenant_id=? AND "
                                            + p.sql(),
                                    Integer.class,
                                    args.toArray()))
                    .isEqualTo(1);
        }
        var page =
                payments.payments(
                        tenant,
                        0,
                        1,
                        new com.rigour.order.application.port.out.OrderSalesPaymentRecordStore
                                .SalesPaymentSearchCriteria(
                                null, null, null, null, null, null, null, null));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        long hidden =
                jdbc.queryForObject(
                        "SELECT id FROM order_payment_record WHERE tenant_id=? AND order_id=?",
                        Long.class,
                        tenant,
                        other);
        assertThat(payments.payment(tenant, hidden)).isEmpty();
        when(iam.authorization(any(), eq("order:payment:delete")))
                .thenReturn(
                        policy(
                                "order:payment:delete",
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "ORDER",
                                                "SELF",
                                                none(),
                                                all(),
                                                none(),
                                                false)),
                                all(),
                                all()));
        assertThatThrownBy(() -> payments.delete(tenant, hidden, 1, actor.userId().toString()))
                .isInstanceOf(AuthorizationDeniedException.class);
        when(iam.authorization(any(), eq("order:fund:read")))
                .thenReturn(
                        policy(
                                "order:fund:read",
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "ORDER",
                                                "ALL",
                                                none(),
                                                all(),
                                                none(),
                                                false)),
                                all(),
                                all()));
        var allFunds = scopes.relatedPredicate("order:fund:read", "order_fund_document");
        var args = new ArrayList<Object>(List.of(tenant));
        args.addAll(allFunds.args());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_fund_document WHERE tenant_id=? AND "
                                        + allFunds.sql(),
                                Integer.class,
                                args.toArray()))
                .isEqualTo(3);
    }

    @Test
    void syncedOrderAndPaymentPersistSourceAuditAndSyncActor() {
        TestAuthorizationContext.set(serviceCallerIdentity());
        Instant sourceCreatedAt = Instant.parse("2026-09-18T02:00:00Z");
        Instant sourceUpdatedAt = Instant.parse("2026-09-19T05:00:00Z");
        Instant syncedAt = Instant.parse("2026-09-21T03:30:00Z");
        var line =
                new com.rigour.order.api.v1.model.SalesOrderLineCommand(
                        10L,
                        11L,
                        "P-SYNC-1",
                        "SKU-SYNC-1",
                        "同步商品",
                        "箱",
                        "BOX",
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.TEN,
                        null,
                        java.math.BigDecimal.ZERO,
                        null);
        var command =
                new com.rigour.order.api.v1.model.SalesOrderCommand(
                        1L,
                        "DINGHUOBAO",
                        "DH.SYNC.0001",
                        "PENDING_OUTBOUND",
                        "U-9",
                        "RY0009",
                        "刘鹏昆",
                        "CUS-1",
                        "杭州客户",
                        "张三",
                        "13800000000",
                        "HZ",
                        null,
                        null,
                        null,
                        null,
                        sourceCreatedAt,
                        "NORMAL",
                        "CASH",
                        List.of(),
                        null,
                        null,
                        null,
                        "同步订单",
                        List.of(line),
                        false,
                        0,
                        "DH.SYNC.0001",
                        sourceCreatedAt,
                        sourceUpdatedAt,
                        "U-10",
                        "张艺瀚",
                        "系统自动同步",
                        syncedAt);
        var created = sales.create(command);

        var orderRow =
                jdbc.queryForMap(
                        "SELECT synced_by,synced_at,source_created_at,source_updated_at,"
                                + "source_modifier_id,source_modifier_name FROM order_sales_order"
                                + " WHERE tenant_id=? AND order_no=?",
                        tenant,
                        created.orderNo());
        assertThat(orderRow)
                .containsEntry("synced_by", "系统自动同步")
                .containsEntry("source_modifier_id", "U-10")
                .containsEntry("source_modifier_name", "张艺瀚");
        assertThat(((java.time.LocalDateTime) orderRow.get("synced_at")).toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(syncedAt);
        assertThat(
                        ((java.time.LocalDateTime) orderRow.get("source_created_at"))
                                .toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(sourceCreatedAt);

        var orderPage =
                register.orders(
                        tenant,
                        0,
                        10,
                        new com.rigour.order.application.port.out.OrderRegisterStore.OrderCriteria(
                                created.orderNo(), null, null, null, null, null, null, null, null,
                                null, null, null, null, null));
        var orderView = orderPage.items().getFirst();
        assertThat(orderView.createdBy()).isEqualTo("刘鹏昆");
        assertThat(orderView.updatedBy()).isEqualTo("张艺瀚");
        assertThat(orderView.syncedBy()).isEqualTo("系统自动同步");
        assertThat(orderView.syncedAt()).isEqualTo(syncedAt);

        // 已有订单走来源投影路径时也要刷新来源审计与同步审计。
        Instant projectedAt = syncedAt.plusSeconds(600);
        sales.updateSourceProjection(
                created.id(),
                new com.rigour.order.api.v1.model.SalesOrderSourceProjectionCommand(
                        "COMPLETED",
                        "U-9",
                        "RY0009",
                        "刘鹏昆",
                        null,
                        null,
                        null,
                        null,
                        "HZ",
                        created.revision(),
                        sourceCreatedAt,
                        projectedAt,
                        "U-11",
                        "王五",
                        "系统自动同步",
                        projectedAt));
        var projectedRow =
                jdbc.queryForMap(
                        "SELECT synced_at,source_updated_at,source_modifier_name"
                                + " FROM order_sales_order WHERE tenant_id=? AND id=?",
                        tenant,
                        created.id());
        assertThat(projectedRow).containsEntry("source_modifier_name", "王五");
        assertThat(
                        ((java.time.LocalDateTime) projectedRow.get("synced_at"))
                                .toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(projectedAt);
        assertThat(
                        ((java.time.LocalDateTime) projectedRow.get("source_updated_at"))
                                .toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(projectedAt);

        var payment =
                payments.create(
                        tenant,
                        "PAY-SYNC-0001",
                        new com.rigour.order.application.port.out.OrderSalesPaymentRecordStore
                                .SalesPaymentWrite(
                                UUID.randomUUID(),
                                "DINGHUOBAO",
                                "SKU-SYNC-R1",
                                created.id(),
                                created.orderNo(),
                                1L,
                                "CUS-1",
                                "杭州客户",
                                "RY0009",
                                "刘鹏昆",
                                sourceUpdatedAt,
                                "CASH",
                                java.math.BigDecimal.TEN,
                                List.of(),
                                "同步回款",
                                0,
                                sourceCreatedAt,
                                sourceUpdatedAt,
                                "U-10",
                                "张艺瀚",
                                "系统自动同步",
                                syncedAt),
                        "SYSTEM");
        var paymentRow =
                jdbc.queryForMap(
                        "SELECT synced_by,synced_at FROM order_payment_record"
                                + " WHERE tenant_id=? AND id=?",
                        tenant,
                        payment.id());
        assertThat(paymentRow).containsEntry("synced_by", "系统自动同步");
        assertThat(
                        ((java.time.LocalDateTime) paymentRow.get("synced_at"))
                                .toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(syncedAt);
        var paymentPage =
                register.payments(
                        tenant,
                        0,
                        10,
                        new com.rigour.order.application.port.out.OrderRegisterStore.PaymentCriteria(
                                null, null, null, null, null, null, null, null, null, null,
                                "PAY-SYNC-0001", null, null, null, null, null, null));
        var paymentView = paymentPage.items().getFirst();
        assertThat(paymentView.syncedBy()).isEqualTo("系统自动同步");
        assertThat(paymentView.syncedAt()).isEqualTo(syncedAt);

        // 回款更新同样刷新同步审计。
        Instant paymentSyncedAt = syncedAt.plusSeconds(900);
        payments.update(
                tenant,
                payment.id(),
                new com.rigour.order.application.port.out.OrderSalesPaymentRecordStore
                        .SalesPaymentWrite(
                        UUID.randomUUID(),
                        "DINGHUOBAO",
                        "SKU-SYNC-R1",
                        created.id(),
                        created.orderNo(),
                        1L,
                        "CUS-1",
                        "杭州客户",
                        "RY0009",
                        "刘鹏昆",
                        sourceUpdatedAt,
                        "CASH",
                        java.math.BigDecimal.TEN,
                        List.of(),
                        "同步回款更新",
                        payment.revision(),
                        sourceCreatedAt,
                        sourceUpdatedAt,
                        "U-10",
                        "张艺瀚",
                        "系统自动同步",
                        paymentSyncedAt),
                "SYSTEM");
        assertThat(
                        ((java.time.LocalDateTime)
                                        jdbc.queryForMap(
                                                        "SELECT synced_at FROM order_payment_record"
                                                                + " WHERE tenant_id=? AND id=?",
                                                        tenant,
                                                        payment.id())
                                                .get("synced_at"))
                                .toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(paymentSyncedAt);
    }

    @Test
    void changedLineLimitIsReadByOrderSaveAndDoesNotChangeAnotherTenant() {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        actor.userId(),
                        actor.tenantId(),
                        actor.userId(),
                        null,
                        actor.sessionId(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:write")));
        var original = parameterStore.maximumManualLines(tenant);
        assertThat(original.value()).isEqualTo("200");
        var saved =
                parameterStore.saveMaximumManualLines(
                        tenant,
                        actor.userId().toString(),
                        new com.rigour.order.api.v1.OrderParameterApi.Change("1", 0, "单行订单管理"));
        var line =
                new com.rigour.order.api.v1.model.SalesOrderLineCommand(
                        10L,
                        11L,
                        "P-1",
                        "SKU-1",
                        "商品",
                        "箱",
                        "BOX",
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.TEN,
                        null,
                        java.math.BigDecimal.ZERO,
                        null);
        var c =
                new com.rigour.order.api.v1.model.SalesOrderCommand(
                        1L,
                        "CUS-1",
                        "杭州客户",
                        "张三",
                        "13800000000",
                        "HZ",
                        null,
                        null,
                        Instant.parse("2026-09-15T01:00:00Z"),
                        "NORMAL",
                        "CASH",
                        null,
                        java.math.BigDecimal.ONE,
                        "备注",
                        List.of(line, line),
                        false,
                        0);
        assertThatThrownBy(() -> sales.create(c)).hasMessageContaining("明细不能超过1条");
        assertThat(parameterStore.maximumManualLines(UUID.randomUUID().toString()).value())
                .isEqualTo("200");
        assertThatThrownBy(
                        () ->
                                parameterStore.saveMaximumManualLines(
                                        tenant,
                                        actor.userId().toString(),
                                        new com.rigour.order.api.v1.OrderParameterApi.Change(
                                                "2", 0, "旧请求")))
                .hasMessageContaining("已被修改");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_parameter_audit WHERE tenant_id=?",
                                Integer.class,
                                tenant))
                .isEqualTo(1);
        parameterStore.saveMaximumManualLines(
                tenant,
                actor.userId().toString(),
                new com.rigour.order.api.v1.OrderParameterApi.Change(
                        "200", saved.revision(), "恢复默认"));
        assertThat(parameterStore.maximumManualLines(tenant).value()).isEqualTo("200");
    }

    @Test
    void freezeUsesCustomerOwnerAndKeepsHistoricalDepartment() {
        long id = order("one", 1L);
        var original =
                attribution("v1", "EMP-A", 10, List.of(10L, 1L), "HZ", List.of("HZ", "ZJ"), true);
        when(customer.resolve(any(), eq(1L))).thenReturn(original);
        tx.executeWithoutResult(s -> writer.prepare(tenant, id, true, false));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT owner_employee_code FROM order_sales_order WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("EMP-A");
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(
                        attribution(
                                "v2",
                                "EMP-B",
                                20,
                                List.of(20L, 2L),
                                "NB",
                                List.of("NB", "ZJ"),
                                true));
        assertThatThrownBy(
                        () -> tx.executeWithoutResult(s -> writer.prepare(tenant, id, true, false)))
                .hasMessageContaining("已冻结");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT department_id FROM order_attribution_snapshot WHERE"
                                        + " order_id=?",
                                Long.class,
                                id))
                .isEqualTo(10L);
        assertThatThrownBy(() -> writer.rejectFrozenSourceRewrite(tenant, id, "EMP-B", "NB"))
                .hasMessageContaining("历史归属");
    }

    @Test
    void missingOwnerStaysDraftAndChangingVersionRollsBack() {
        long id = order("missing", 1L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(attribution("v0", null, 10, List.of(10L), "HZ", List.of("HZ"), false));
        tx.executeWithoutResult(s -> writer.prepare(tenant, id, true, true));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT order_status_code FROM order_sales_order WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("DRAFT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT owner_employee_code FROM order_sales_order WHERE id=?",
                                String.class,
                                id))
                .isNull();
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(
                        attribution("v1", "EMP-A", 10, List.of(10L), "HZ", List.of("HZ"), true),
                        attribution("v2", "EMP-B", 20, List.of(20L), "NB", List.of("NB"), true));
        assertThatThrownBy(
                        () -> tx.executeWithoutResult(s -> writer.prepare(tenant, id, true, false)))
                .hasMessageContaining("刚刚变更");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM order_attribution_snapshot WHERE order_id=?",
                                String.class,
                                id))
                .isEqualTo("REVIEW");
    }

    @Test
    void departmentFilterKeepsFrozenSnapshotDepartmentAfterTransfer() {
        long id = order("dept-snapshot", 1L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(attribution("v1", "EMP-A", 10, List.of(10L), "HZ", List.of("HZ"), true));
        tx.executeWithoutResult(s -> writer.prepare(tenant, id, true, false));

        // 业务员调岗：当前归属字段改到新部门/新业务员，历史快照仍冻结在部门 10
        jdbc.update("UPDATE order_sales_order SET owner_employee_code='EMP-B' WHERE id=?", id);

        // 数据范围授权：本用例独立声明 order:read 全量可见
        when(iam.authorization(any(), eq("order:read")))
                .thenReturn(
                        policy(
                                "order:read",
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "ORDER",
                                                "ALL",
                                                none(),
                                                all(),
                                                none(),
                                                false)),
                                all(),
                                all()));
        try (var ctx =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.open(
                        iam, actor, policy("order:read", List.of(), all(), all()))) {
            assertThat(ordersInDepartments(Set.of(10L))).contains("dept-snapshot");
            assertThat(ordersInDepartments(Set.of(20L))).doesNotContain("dept-snapshot");
        }
    }

    private List<String> ordersInDepartments(Set<Long> departmentIds) {
        return register
                .orders(
                        tenant,
                        0,
                        50,
                        new com.rigour.order.application.port.out.OrderRegisterStore.OrderCriteria(
                                null, null, null, null, null, null, departmentIds, null, null,
                                null, null, null, null, null))
                .items()
                .stream()
                .map(item -> item.orderNo())
                .toList();
    }

    @Test
    void warehouseQueueFiltersBeforeCountAndDoesNotExposeOutOfScopeDetails() {
        long mine = order("queue-mine", 1L), other = order("queue-other", 2L);
        jdbc.update(
                "UPDATE order_sales_order SET"
                        + " order_status_code='SUBMITTED',selected_warehouse_id=100 WHERE id=?",
                mine);
        jdbc.update(
                "UPDATE order_sales_order SET"
                        + " order_status_code='SUBMITTED',selected_warehouse_id=200 WHERE id=?",
                other);
        var rule =
                new Clause(
                        UUID.randomUUID(),
                        "FULFILLMENT",
                        "WAREHOUSE",
                        none(),
                        none(),
                        specified("100"),
                        true);
        when(iam.authorization(any(), eq("order:outbound:read")))
                .thenReturn(policy("order:outbound:read", List.of(rule), none(), specified("100")));
        var page = fulfillment.queue(tenant, 0, 1, null, "PENDING");
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items())
                .extracting(com.rigour.order.api.v1.model.OrderFulfillmentQueueView.Item::orderId)
                .containsExactly(String.valueOf(mine));
        assertThat(fulfillment.detail(tenant, mine).order().orderId())
                .isEqualTo(String.valueOf(mine));
        assertThatThrownBy(() -> fulfillment.detail(tenant, other))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(fulfillment.queue(tenant, 1, 1, null, "PENDING").items()).isEmpty();
    }

    @Test
    void clausesDoNotCrossCombineRegionsAndWarehouses() {
        long id = order("warehouse", 1L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(
                        attribution(
                                "v1",
                                "EMP-A",
                                10,
                                List.of(10L, 1L),
                                "HZ",
                                List.of("HZ", "ZJ"),
                                true));
        tx.executeWithoutResult(s -> writer.prepare(tenant, id, true, false));
        jdbc.update("UPDATE order_sales_order SET selected_warehouse_id=100 WHERE id=?", id);
        var hz =
                new Clause(
                        UUID.randomUUID(),
                        "FULFILLMENT",
                        "WAREHOUSE",
                        none(),
                        specified("HZ"),
                        specified("100"),
                        true);
        var nb =
                new Clause(
                        UUID.randomUUID(),
                        "FULFILLMENT",
                        "WAREHOUSE",
                        none(),
                        specified("NB"),
                        specified("200"),
                        true);
        when(iam.authorization(any(), eq("order:outbound:confirm")))
                .thenReturn(
                        policy(
                                "order:outbound:confirm",
                                List.of(hz, nb),
                                all(),
                                specified("100", "200")));
        when(iam.authorization(any(), eq("order:warehouse:select")))
                .thenReturn(
                        policy(
                                "order:warehouse:select",
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "ORDER",
                                                "REGION",
                                                none(),
                                                specified("HZ"),
                                                specified("100"),
                                                true),
                                        new Clause(
                                                UUID.randomUUID(),
                                                "ORDER",
                                                "REGION",
                                                none(),
                                                specified("NB"),
                                                specified("200"),
                                                true)),
                                all(),
                                specified("100", "200")));
        assertThat(scopes.permittedWarehouses(tenant, id, List.of(100L, 200L, 300L)))
                .containsExactly(100L);
        scopes.requireOrder(tenant, id, "order:outbound:confirm", 100L);
        assertThatThrownBy(() -> scopes.requireOrder(tenant, id, "order:outbound:confirm", 200L))
                .isInstanceOf(AuthorizationDeniedException.class);
        var dept =
                new Clause(
                        UUID.randomUUID(),
                        "ORDER",
                        "DEPARTMENT",
                        specified("1"),
                        none(),
                        none(),
                        true);
        when(iam.authorization(any(), eq("order:read")))
                .thenReturn(policy("order:read", List.of(dept), all(), none()));
        scopes.requireOrder(tenant, id, "order:read");
        when(iam.authorization(any(), eq("order:read")))
                .thenReturn(
                        policy(
                                "order:read",
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "ORDER",
                                                "DEPARTMENT",
                                                specified("2"),
                                                none(),
                                                none(),
                                                true)),
                                all(),
                                none()));
        assertThatThrownBy(() -> scopes.requireOrder(tenant, id, "order:read"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void warehouseSelectionIsLockedAndResultReconciliationIsIdempotent() {
        when(iam.authorization(any(), anyString()))
                .thenAnswer(
                        i ->
                                policy(
                                        i.getArgument(1),
                                        List.of(
                                                new Clause(
                                                        UUID.randomUUID(),
                                                        "FULFILLMENT",
                                                        "ALL",
                                                        none(),
                                                        all(),
                                                        all(),
                                                        true)),
                                        all(),
                                        all()));
        long id = order("fulfill", 1L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(
                        attribution("v1", "EMP-A", 10, List.of(10L), "HZ", List.of("HZ"), true));
        tx.executeWithoutResult(s -> writer.prepare(tenant, id, true, false));
        jdbc.update("UPDATE order_sales_order SET order_status_code='SUBMITTED' WHERE id=?", id);
        jdbc.update(
                "INSERT INTO"
                    + " order_sales_order_line(tenant_id,order_id,line_no,product_id,product_variant_id,product_name_snapshot,unit_code,quantity,unit_price,line_amount)"
                    + " VALUES(?,?,1,10,11,'商品','PCS',2,5,10)",
                tenant,
                id);
        var selected = fulfillment.select(actor, id, 100, 1);
        assertThat(selected.orderRevision()).isEqualTo(2);
        assertThatThrownBy(() -> fulfillment.prepare(actor, id, 200, 2, null, null))
                .hasMessageContaining("已确认的仓库");
        var prepared =
                fulfillment.prepare(actor, id, 100, 2, Instant.parse("2026-09-15T01:00:00Z"), "测试");
        assertThat(fulfillment.prepare(actor, id, 100, 2, null, null).executionId())
                .isEqualTo(prepared.executionId());
        assertThatThrownBy(() -> fulfillment.select(actor, id, 200, 2))
                .hasMessageContaining("已开始履约");
        assertThatThrownBy(
                        () -> tx.executeWithoutResult(s -> writer.requireNoExecution(tenant, id)))
                .hasMessageContaining("执行记录");
        assertThat(fulfillment.claim(actor, prepared.executionId()).lines()).hasSize(1);
        when(iam.authorization(any(), eq("order:outbound:confirm")))
                .thenReturn(
                        new SupplyAuthorizationView(
                                "ACTIVE",
                                actor.tenantId(),
                                actor.userId(),
                                "EMP-A",
                                2,
                                2,
                                1,
                                1,
                                Set.of(),
                                "order:outbound:confirm",
                                false,
                                List.of(),
                                all(),
                                all()));
        assertThatThrownBy(() -> fulfillment.claim(actor, prepared.executionId()))
                .isInstanceOf(AuthorizationDeniedException.class);
        // 恢复专用用例可记录已经发生的 ERP 结果，不能借此新建执行意图。
        var done =
                fulfillment.complete(
                        tenant,
                        prepared.executionId(),
                        999,
                        "CK-999",
                        prepared.stockOutTime(),
                        100);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.orderRevision()).isEqualTo(3);
        assertThat(
                        fulfillment
                                .complete(
                                        tenant,
                                        prepared.executionId(),
                                        999,
                                        "CK-999",
                                        prepared.stockOutTime(),
                                        100)
                                .orderRevision())
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outbound_status_code FROM order_sales_order WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("OUT_CONFIRMED");
        assertThatThrownBy(
                        () ->
                                fulfillment.complete(
                                        tenant,
                                        prepared.executionId(),
                                        998,
                                        "CK-998",
                                        prepared.stockOutTime(),
                                        100))
                .hasMessageContaining("不一致");
    }

    @Autowired com.rigour.order.application.port.out.OrderSalesShipmentStore shipments;

    @Test
    void manualShipmentCannotInventWarehouseOrStockOutReceipt() {
        long id = order("shipment-binding", 1L);
        jdbc.update(
                "UPDATE order_sales_order SET selected_warehouse_id=100 WHERE tenant_id=? AND id=?",
                tenant,
                id);
        when(iam.authorization(any(), any()))
                .thenAnswer(
                        call ->
                                policy(
                                        call.getArgument(1),
                                        List.of(
                                                new Clause(
                                                        UUID.randomUUID(),
                                                        "ORDER",
                                                        "ALL",
                                                        none(),
                                                        none(),
                                                        none(),
                                                        true)),
                                        all(),
                                        all()));
        try (var ctx =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.open(
                        iam, actor, policy("order:shipment:create", List.of(), all(), all()))) {
            assertThatThrownBy(
                            () ->
                                    shipments.create(
                                            tenant,
                                            "wrong-wh",
                                            shipmentWrite(id, 200L, null, null, "CREATED"),
                                            "actor"))
                    .hasMessageContaining("选仓一致");
            assertThatThrownBy(
                            () ->
                                    shipments.create(
                                            tenant,
                                            "fake-stock",
                                            shipmentWrite(id, 100L, 999L, "FAKE", "CREATED"),
                                            "actor"))
                    .hasMessageContaining("ERP 回执");
            assertThatThrownBy(
                            () ->
                                    shipments.create(
                                            tenant,
                                            "premature",
                                            shipmentWrite(id, 100L, null, null, "SHIPPED"),
                                            "actor"))
                    .hasMessageContaining("尚无已完成");
            var draft =
                    shipments.create(
                            tenant,
                            "logistics-draft",
                            shipmentWrite(id, null, null, null, "CREATED"),
                            "actor");
            assertThat(draft.warehouseId()).isEqualTo(100L);
            assertThat(draft.stockOutOrderId()).isNull();
            jdbc.update(
                    "INSERT INTO"
                        + " order_fulfillment_execution(tenant_id,order_id,execution_id,warehouse_id,order_revision,request_json,request_hash,actor_id,status,erp_stock_out_id,erp_stock_out_no)"
                        + " VALUES(?,?,?,100,1,JSON_OBJECT(),?,'actor','COMPLETED',900,'CK-900')",
                    tenant,
                    id,
                    UUID.randomUUID().toString(),
                    "0".repeat(64));
            var shipped =
                    shipments.create(
                            tenant,
                            "actual-shipped",
                            shipmentWrite(id, null, null, null, "SHIPPED"),
                            "actor");
            assertThat(shipped.stockOutOrderId()).isEqualTo(900L);
            assertThat(shipped.stockOutNo()).isEqualTo("CK-900");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM order_sales_shipment WHERE tenant_id=?",
                                    Integer.class,
                                    tenant))
                    .isEqualTo(2);
        }
    }

    private com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentWrite
            shipmentWrite(long id, Long wh, Long stock, String stockNo, String status) {
        return new com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentWrite(
                id,
                "shipment-binding",
                1L,
                "C1",
                "客户",
                null,
                "HZ",
                "EMP-A",
                wh,
                stock,
                stockNo,
                status,
                null,
                null,
                Instant.now(),
                java.math.BigDecimal.ZERO,
                List.of(),
                null,
                0);
    }

    @Test
    void preparingDataComparisonUsesActualFrozenOrderScopeWithoutFilteringTheLegacyRequest() {
        long own = order("shadow-own", 1L), other = order("shadow-other", 2L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(
                        attribution("own", "EMP-A", 10, List.of(10L), "HZ", List.of("HZ"), true));
        when(customer.resolve(any(), eq(2L)))
                .thenReturn(
                        attribution("other", "EMP-B", 10, List.of(10L), "HZ", List.of("HZ"), true));
        tx.executeWithoutResult(
                t -> {
                    writer.prepare(tenant, own, true, false);
                    writer.prepare(tenant, other, true, false);
                });
        jdbc.update(
                "UPDATE order_sales_order SET order_status_code='SUBMITTED' WHERE tenant_id=?",
                tenant);
        var clauses =
                List.of(
                        new Clause(
                                UUID.randomUUID(), "ORDER", "SELF", none(), all(), none(), false));
        var p =
                new SupplyAuthorizationView(
                        "PREPARING",
                        actor.tenantId(),
                        actor.userId(),
                        "EMP-A",
                        7,
                        1,
                        1,
                        1,
                        Set.of("order:read"),
                        "order:read",
                        true,
                        clauses,
                        all(),
                        all());
        when(iam.candidate(any(), eq("order:read"))).thenReturn(p);
        try (var ctx =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.open(iam, actor, p)) {
            scopes.compareRecord(tenant, own, "order:read", true, null);
            scopes.compareRecord(tenant, other, "order:read", true, null);
            assertThat(scopes.predicate("order:read", "o.", null).sql()).isEqualTo("1=1");
            assertThat(ctx.active()).isFalse();
        }
        var captured =
                org.mockito.ArgumentCaptor.forClass(
                        com.rigour.tenant.iam.api.v1.model.SupplyDataObservation.class);
        verify(iam, times(2)).observeData(any(), captured.capture());
        assertThat(captured.getAllValues())
                .extracting(
                        com.rigour.tenant.iam.api.v1.model.SupplyDataObservation::proposedAllowed)
                .containsExactly(true, false);
        assertThat(captured.getAllValues())
                .allMatch(com.rigour.tenant.iam.api.v1.model.SupplyDataObservation::legacyAllowed);
    }

    private void reviewPermissions() {
        for (String action :
                List.of(
                        "order:attribution:read",
                        "order:attribution:propose",
                        "order:attribution:approve"))
            when(iam.authorization(any(), eq(action)))
                    .thenReturn(
                            policy(
                                    action,
                                    List.of(
                                            new Clause(
                                                    UUID.randomUUID(),
                                                    "ORDER",
                                                    "ALL",
                                                    none(),
                                                    all(),
                                                    none(),
                                                    false)),
                                    all(),
                                    all()));
    }

    private void otherReviewer() {
        UUID reviewer = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        reviewer,
                        UUID.fromString(tenant),
                        reviewer,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:attribution:approve")));
    }

    @Test
    void reviewedHistoricalAttributionPreservesEvidenceUnknownDepartmentAndAmounts() {
        reviewPermissions();
        long id = order("historical-review", 1L);
        jdbc.update(
                "UPDATE order_sales_order SET"
                    + " source_system_code='FEISHU',source_order_no='original-123',order_status_code='SUBMITTED'"
                    + " WHERE tenant_id=? AND id=?",
                tenant,
                id);
        var before =
                jdbc.queryForMap(
                        "SELECT payable_amount,paid_amount,order_status_code,outbound_status_code"
                            + " FROM order_sales_order WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        var context = attributionReview.context(id);
        var proposed =
                new com.rigour.order.api.v1.model.OrderAttributionReview.Snapshot(
                        "EMP-HISTORY", "当时姓名", null, null, List.of(), "HZ", List.of("ZJ", "HZ"));
        var command =
                new com.rigour.order.api.v1.model.OrderAttributionReview.Propose(
                        context.orderRevision(),
                        0L,
                        proposed,
                        "归档订单 original-123",
                        "原单业务员与 HR 编码映射已复核；没有历史部门证据",
                        "补齐历史员工与城市");
        var review = attributionReview.propose(id, command);
        assertThat(review.status()).isEqualTo("PENDING");
        assertThat(review.before()).containsKey("order");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_attribution_snapshot WHERE tenant_id=?"
                                    + " AND order_id=?",
                                Integer.class,
                                tenant,
                                id))
                .isZero();
        assertThatThrownBy(
                        () ->
                                attributionReview.decide(
                                        id,
                                        review.id(),
                                        new com.rigour.order.api.v1.model.OrderAttributionReview
                                                .Review(true, "已核对")))
                .hasMessageContaining("另一位");
        otherReviewer();
        var applied =
                attributionReview.decide(
                        id,
                        review.id(),
                        new com.rigour.order.api.v1.model.OrderAttributionReview.Review(
                                true, "已核对归档原单"));
        assertThat(applied.status()).isEqualTo("APPLIED");
        var snapshot =
                jdbc.queryForMap(
                        "SELECT * FROM order_attribution_snapshot WHERE tenant_id=? AND order_id=?",
                        tenant,
                        id);
        assertThat(snapshot.get("employee_name")).isEqualTo("当时姓名");
        assertThat(snapshot.get("department_id")).isNull();
        assertThat(snapshot.get("department_path").toString()).isEqualTo("[]");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT"
                                    + " payable_amount,paid_amount,order_status_code,outbound_status_code"
                                    + " FROM order_sales_order WHERE tenant_id=? AND id=?",
                                tenant,
                                id))
                .isEqualTo(before);
        assertThat(snapshot.get("source_version")).isEqualTo("REVIEW:" + review.id());
        assertThatThrownBy(
                        () ->
                                attributionReview.decide(
                                        id,
                                        review.id(),
                                        new com.rigour.order.api.v1.model.OrderAttributionReview
                                                .Review(true, "重复")))
                .hasMessageContaining("已处理");
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        t ->
                                                writer.rejectFrozenSourceRewrite(
                                                        tenant, id, "CURRENT-OWNER", "HZ")))
                .hasMessageContaining("不能改写");
    }

    @Test
    void attributionApprovalCannotMoveOrderOutsideReviewerScopeAndStaleProposalCannotApply() {
        reviewPermissions();
        long id = order("review-scope", 1L);
        when(customer.resolve(any(), eq(1L)))
                .thenReturn(
                        attribution(
                                "original",
                                "EMP-A",
                                10,
                                List.of(1L, 10L),
                                "HZ",
                                List.of("ZJ", "HZ"),
                                true));
        tx.executeWithoutResult(t -> writer.prepare(tenant, id, true, false));
        jdbc.update(
                "UPDATE order_sales_order SET order_status_code='SUBMITTED' WHERE tenant_id=? AND"
                    + " id=?",
                tenant,
                id);
        var original = attributionReview.context(id);
        var request =
                new com.rigour.order.api.v1.model.OrderAttributionReview.Propose(
                        original.orderRevision(),
                        original.snapshotRevision(),
                        new com.rigour.order.api.v1.model.OrderAttributionReview.Snapshot(
                                "EMP-B", "旧姓名", null, null, List.of(), "NB", List.of("ZJ", "NB")),
                        "归档证据",
                        "原单与员工映射",
                        "更正");
        var review = attributionReview.propose(id, request);
        when(iam.authorization(any(), eq("order:attribution:approve")))
                .thenReturn(
                        policy(
                                "order:attribution:approve",
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "ORDER",
                                                "REGION",
                                                none(),
                                                specified("HZ"),
                                                none(),
                                                true)),
                                all(),
                                all()));
        otherReviewer();
        assertThatThrownBy(
                        () ->
                                attributionReview.decide(
                                        id,
                                        review.id(),
                                        new com.rigour.order.api.v1.model.OrderAttributionReview
                                                .Review(true, "已核对")))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT region_code FROM order_attribution_snapshot WHERE"
                                    + " tenant_id=? AND order_id=?",
                                String.class,
                                tenant,
                                id))
                .isEqualTo("HZ");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM order_attribution_adjustment WHERE tenant_id=?"
                                    + " AND id=?",
                                String.class,
                                tenant,
                                review.id()))
                .isEqualTo("PENDING");
        jdbc.update(
                "UPDATE order_sales_order SET revision=revision+1 WHERE tenant_id=? AND id=?",
                tenant,
                id);
        assertThatThrownBy(
                        () ->
                                attributionReview.decide(
                                        id,
                                        review.id(),
                                        new com.rigour.order.api.v1.model.OrderAttributionReview
                                                .Review(true, "已核对")))
                .hasMessageContaining("已变化");
        assertThatThrownBy(
                        () ->
                                attributionReview.decide(
                                        id + 1000,
                                        review.id(),
                                        new com.rigour.order.api.v1.model.OrderAttributionReview
                                                .Review(false, "错误订单")))
                .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
    }

    private CallerIdentity serviceCallerIdentity() {
        return new CallerIdentity(
                "SERVICE",
                UUID.randomUUID(),
                actor.tenantId(),
                null,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of("DHB_ORDER_SYNC_SERVICE"),
                Set.of("order:read", "order:write"));
    }

    private long order(String no, Long customerId) {
        jdbc.update(
                "INSERT INTO"
                    + " order_sales_order(tenant_id,order_no,customer_id,customer_name_snapshot,order_date,owner_employee_code,created_by)"
                    + " VALUES(?,?,?,?,UTC_TIMESTAMP(6),'UNTRUSTED',?)",
                tenant,
                no,
                customerId,
                "测试客户",
                actor.principalId().toString());
        return jdbc.queryForObject(
                "SELECT id FROM order_sales_order WHERE tenant_id=? AND order_no=?",
                Long.class,
                tenant,
                no);
    }

    private CustomerOrderAttributionView attribution(
            String version,
            String employee,
            long dept,
            List<Long> deps,
            String region,
            List<String> regions,
            boolean usable) {
        return new CustomerOrderAttributionView(
                tenant,
                1,
                "C1",
                "客户",
                employee,
                "主责姓名",
                dept,
                "部门",
                deps,
                region,
                regions,
                version,
                1,
                1,
                1,
                Instant.parse("2026-09-15T01:00:00Z"),
                usable,
                usable ? null : "客户尚未分配主责员工");
    }

    private SupplyAuthorizationView policy(
            String action, List<Clause> clauses, Limit region, Limit warehouse) {
        return new SupplyAuthorizationView(
                "ACTIVE",
                actor.tenantId(),
                actor.userId(),
                "EMP-A",
                1,
                1,
                1,
                1,
                Set.of(action),
                action,
                true,
                clauses,
                region,
                warehouse);
    }

    private static Limit none() {
        return new Limit("NONE", List.of());
    }

    private static Limit all() {
        return new Limit("ALL", List.of());
    }

    private static Limit specified(String... refs) {
        return new Limit("SPECIFIED", List.of(refs));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.rigour.order.application.port.out.SupplyReadinessStore supplyReadiness;

    @org.junit.jupiter.api.Test
    void readinessChecksRunAgainstTheMigratedTenantSchema() {
        var report = supplyReadiness.inspect(java.util.UUID.randomUUID().toString());
        org.assertj.core.api.Assertions.assertThat(report.contractVersion()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(report.version()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(report.checks()).allMatch(c -> c.count() == 0);
    }
}
