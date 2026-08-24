package com.rigour.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.application.service.dhb.DhbOrderSyncService;
import com.rigour.integration.infrastructure.media.ProductMediaSyncWorker;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.DhbConnectorEntity;
import com.rigour.integration.infrastructure.persistence.entity.ExternalObjectMappingEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationOrderMirrorEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationOutboxEventEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationRawLandingEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncCheckpointEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncRunEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationSyncTaskEntity;
import com.rigour.integration.infrastructure.persistence.mapper.DhbConnectorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.ExternalObjectMappingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationOrderMirrorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationOutboxEventMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationRawLandingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncCheckpointMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncRunMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncTaskMapper;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordCommand;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesShipmentCommand;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.api.v1.model.SalesShipmentLineView;
import com.rigour.shared.context.CallerIdentity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class IntegrationMigrationServiceApplicationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_integration")
            .withUsername("rigour_integration_test")
            .withPassword("rigour_integration_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/testdata");
        registry.add("rigour.integration.product-media.cos.region", () -> "ap-beijing");
        registry.add("rigour.integration.product-media.cos.bucket", () -> "rigour-test-1250000000");
        registry.add("rigour.integration.product-media.cos.secret-id", () -> "test-secret-id");
        registry.add("rigour.integration.product-media.cos.secret-key", () -> "test-secret-key");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private DhbIntegrationStore store;

    @Autowired
    private DhbSyncStore syncStore;

    @Autowired
    private DhbConnectorMapper connectorMapper;

    @Autowired
    private IntegrationSyncTaskMapper syncTaskMapper;

    @Autowired
    private IntegrationSyncRunMapper syncRunMapper;

    @Autowired
    private IntegrationRawLandingMapper rawLandingMapper;

    @Autowired
    private IntegrationOrderMirrorMapper orderMirrorMapper;

    @Autowired
    private IntegrationOutboxEventMapper outboxEventMapper;

    @Autowired
    private ExternalObjectMappingMapper externalObjectMappingMapper;

    @Autowired
    private IntegrationSyncCheckpointMapper checkpointMapper;

    @MockitoBean
    private ProductMediaSyncWorker productMediaSyncWorker;

    @Test
    void contextLoadsAndMigratesIntegrationSchema() {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .contains("1", "2", "3", "4", "11", "12", "13");
        assertThat(syncRunMapper.selectCount(Wrappers.<IntegrationSyncRunEntity>query())).isZero();
        assertThat(rawLandingMapper.selectCount(Wrappers.<IntegrationRawLandingEntity>query()))
                .isGreaterThanOrEqualTo(0L);
        assertThat(orderMirrorMapper.selectCount(Wrappers.<IntegrationOrderMirrorEntity>query()))
                .isGreaterThanOrEqualTo(0L);
        assertThat(outboxEventMapper.selectCount(Wrappers.<IntegrationOutboxEventEntity>query()))
                .isGreaterThanOrEqualTo(0L);
        assertThat(checkpointMapper.selectCount(Wrappers.<IntegrationSyncCheckpointEntity>query()))
                .isGreaterThanOrEqualTo(0L);
    }

    @Test
    void v11DoesNotInferHistoricalProvenanceFromReboundTask() {
        UUID ambiguousTenant = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID currentConnector = UUID.fromString("20000000-0000-0000-0000-000000000002");
        UUID singleTenant = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID singleConnector = UUID.fromString("20000000-0000-0000-0000-000000000003");

        assertThat(connectorIdFromTask(ambiguousTenant, "HISTORY_REBOUND")).isEqualTo(currentConnector);
        assertThat(connectorIdFromRawLanding(ambiguousTenant, "HIST-REBOUND")).isNull();
        assertThat(connectorIdFromOrderMirror(ambiguousTenant, "HIST-REBOUND")).isNull();

        assertThat(connectorIdFromRawLanding(singleTenant, "HIST-SINGLE")).isEqualTo(singleConnector);
        assertThat(connectorIdFromOrderMirror(singleTenant, "HIST-SINGLE")).isEqualTo(singleConnector);
    }

    @Test
    void persistsConnectorTaskAndFieldMappingWithTenantBoundary() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        ConnectorView connector = store.createConnector(tenantA, actor,
                new ConnectorCommand("DHB_MAIN", "订货宝主连接",
                        "https://open.dhb.example", "env://DHB_TEST", "ACTIVE", 0));
        store.recordConnectionTest(tenantA, actor, connector.id(),
                ConnectionTestResult.failure("DHB_AUTH_FAILED", "认证失败"));
        store.saveFieldMapping(tenantA, actor, null,
                new FieldMappingCommand(connector.id(), "orderId", "sourceOrderId", "DIRECT", true, 0));

        assertThat(store.connectors(tenantA)).extracting(ConnectorView::code).contains("DHB_MAIN");
        assertThat(store.connectors(tenantB)).isEmpty();
        List<SyncTaskView> defaultTasks = store.syncTasks(tenantA);
        assertThat(defaultTasks).extracting(SyncTaskView::code).containsExactlyInAnyOrder(
                "DHB_ORDER_DEFAULT", "DHB_PRODUCT_MASTER_DEFAULT", "DHB_SUPPLY_CHAIN_DEFAULT",
                "DHB_CRM_MASTER_DEFAULT", "DHB_BUSINESS_DICTIONARY_DEFAULT");
        assertThat(defaultTasks).filteredOn(task -> "ORDER".equals(task.objectType()))
                .singleElement().satisfies(task -> {
            assertThat(task.connectorId()).isEqualTo(connector.id());
            assertThat(task.code()).isEqualTo("DHB_ORDER_DEFAULT");
            assertThat(task.objectType()).isEqualTo("ORDER");
            assertThat(task.status()).isEqualTo("IDLE");
        });
        assertThatThrownBy(() -> store.createSyncTask(tenantA, actor,
                new SyncTaskCommand(connector.id(), "ORDER_PULL", "ORDER", "IDLE", null, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只允许一个ORDER同步任务");
        assertThat(store.fieldMappings(tenantA, connector.id())).hasSize(1);
        assertThat(connectorRow(tenantA, connector.id()).credentialStatus).isEqualTo("INVALID");
        assertThatThrownBy(() -> store.fieldMappings(tenantB, connector.id()))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
    }

    @Test
    void discoversOnlyEnabledOrderTasksWithActiveConnectorsAcrossTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        ConnectorView activeConnector = store.createConnector(tenantA, actor,
                new ConnectorCommand("DHB_DISCOVERY_ACTIVE", "启用连接",
                        "https://open.dhb.example", "env://DHB_DISCOVERY_ACTIVE", "ACTIVE", 0));
        var activeOrderTask = orderTaskFor(tenantA, activeConnector.id());

        ConnectorView disabledConnector = store.createConnector(tenantB, actor,
                new ConnectorCommand("DHB_DISCOVERY_DISABLED", "禁用连接",
                        "https://open.dhb.example", "env://DHB_DISCOVERY_DISABLED", "DISABLED", 0));
        var disabledConnectorTask = orderTaskFor(tenantB, disabledConnector.id());

        ConnectorView disabledTaskConnector = store.createConnector(tenantA, actor,
                new ConnectorCommand("DHB_DISCOVERY_DISABLED_TASK", "禁用任务连接",
                        "https://open.dhb.example", "env://DHB_DISCOVERY_DISABLED_TASK", "ACTIVE", 0));
        var disabledTask = orderTaskFor(tenantA, disabledTaskConnector.id());
        updateTask(disabledTask.id(), "enabled", 0);

        ConnectorView productConnector = store.createConnector(tenantA, actor,
                new ConnectorCommand("DHB_DISCOVERY_PRODUCT", "商品连接",
                        "https://open.dhb.example", "env://DHB_DISCOVERY_PRODUCT", "ACTIVE", 0));
        var productOrderTask = orderTaskFor(tenantA, productConnector.id());
        updateTask(productOrderTask.id(), "enabled", 0);
        var productTask = store.createSyncTask(tenantA, actor,
                new SyncTaskCommand(productConnector.id(), "PRODUCT_ACTIVE", "PRODUCT", "IDLE", null, 0));

        ConnectorView pausedConnector = store.createConnector(tenantA, actor,
                new ConnectorCommand("DHB_DISCOVERY_PAUSED", "暂停连接",
                        "https://open.dhb.example", "env://DHB_DISCOVERY_PAUSED", "ACTIVE", 0));
        var pausedTask = orderTaskFor(tenantA, pausedConnector.id());
        updateTask(pausedTask.id(), "task_status", "PAUSED");

        List<SyncTargetView> targets = store.activeOrderSyncTargets();

        assertThat(targets).extracting(SyncTargetView::taskId).contains(activeOrderTask.id());
        assertThat(targets).extracting(SyncTargetView::taskId)
                .doesNotContain(disabledConnectorTask.id(), disabledTask.id(), productOrderTask.id(),
                        productTask.id(), pausedTask.id());
    }

    @Test
    void manuallyPullsOrdersIntoRawMirrorAndOutboxIdempotently() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ConnectorView connector = store.createConnector(tenant, actor,
                new ConnectorCommand("DHB_SYNC", "订货宝同步",
                        "https://erp.dhb168.example/home/index/erpIndex", "env://RIGOUR_DHB_TEST", "ACTIVE", 0));
        var task = orderTaskFor(tenant, connector.id());

        Instant from = Instant.parse("2026-08-04T00:00:00Z");
        Instant to = Instant.parse("2026-08-04T01:00:00Z");
        DhbClient.OrderSummary first = order("DHB-1", "12.50");
        DhbClient.OrderSummary second = order("DHB-2", "8.00");
        DhbClient client = mock(DhbClient.class);
        when(client.getOrders(any(), any())).thenAnswer(invocation -> {
            DhbClient.OrderQuery query = invocation.getArgument(1);
            return query.page().begin() == 0
                    ? new DhbClient.Page<>(query.page(), 2, List.of(first))
                    : new DhbClient.Page<>(query.page(), 2, List.of(second));
        });
        when(client.getOrderContent(any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> orderDetail(invocation.getArgument(1)));
        stubEmptyDependentOrderFeeds(client);
        seedOrderProjectionMappings(tenant, connector.id());

        CallerIdentity caller = new CallerIdentity("TENANT", actor, tenant, actor, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("integration:dhb:write"));
        FakeOrderProjectionClient orderProjection = new FakeOrderProjectionClient();
        DhbOrderSyncService worker = new DhbOrderSyncService(syncStore, client, orderProjection,
                new FakeIamDhbStaffSyncClient());

        var result = worker.runOrderPull(caller, task.id(), new SyncRunCommand(from, to, 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(orderProjection.created).isEqualTo(2);
        assertThat(orderProjection.rows.values()).extracting(SalesOrderDetailView::ownerStaffCode)
                .containsOnly("RY202608220001");
        assertThat(orderProjection.rows.values()).extracting(SalesOrderDetailView::ownerStaffNameSnapshot)
                .containsOnly("刘彦");
        assertThat(rawLandingCount(tenant)).isEqualTo(4L);
        assertThat(orderMirrorCount(tenant)).isEqualTo(2L);
        assertThat(outboxEventCount(tenant)).isEqualTo(2L);
        assertThat(externalMappingCount(tenant, "SALES_ORDER")).isEqualTo(2L);
        assertThat(checkpoint(tenant, task.id()).cursorValue).isEqualTo(to.toString());

        var repeated = worker.runOrderPull(caller, task.id(), new SyncRunCommand(from, to, 1));

        assertThat(repeated.status()).isEqualTo("SUCCEEDED");
        assertThat(repeated.acceptedCount()).isZero();
        assertThat(repeated.duplicateCount()).isEqualTo(2);
        assertThat(orderProjection.created).isEqualTo(2);
        assertThat(rawLandingCount(tenant)).isEqualTo(4L);
        assertThat(outboxEventCount(tenant)).isEqualTo(2L);
    }

    @Test
    void defaultOrderPullUsesFullSourceFeedWithoutCheckpointWindow() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ConnectorView connector = store.createConnector(tenant, actor,
                new ConnectorCommand("DHB_FULL_DEFAULT", "订货宝默认全量",
                        "https://full-default.dhb.example", "env://DHB_FULL_DEFAULT", "ACTIVE", 0));
        var task = orderTaskFor(tenant, connector.id());

        DhbClient.OrderSummary source = order("DHB-FULL-1", "12.50");
        DhbClient client = mock(DhbClient.class);
        when(client.getOrders(any(), any())).thenAnswer(invocation -> {
            DhbClient.OrderQuery query = invocation.getArgument(1);
            assertThat(query.orderStatus()).isEqualTo("all");
            assertThat(query.createdWindow()).isNull();
            assertThat(query.updatedWindow()).isNull();
            assertThat(query.exceptionStatus()).isEqualTo("all");
            assertThat(query.apiStatus()).isEqualTo("all");
            return new DhbClient.Page<>(query.page(), 1,
                    query.page().begin() == 0 ? List.of(source) : List.of());
        });
        when(client.getOrderContent(any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> orderDetail(invocation.getArgument(1)));
        when(client.getShipments(any(), any())).thenAnswer(invocation -> {
            DhbClient.ShipmentQuery query = invocation.getArgument(1);
            assertThat(query.createdWindow()).isNull();
            assertThat(query.updatedWindow()).isNull();
            assertThat(query.isApi()).isNull();
            return new DhbClient.Page<>(query.page(), 0, List.of());
        });
        when(client.getReceipts(any(), any())).thenAnswer(invocation -> {
            DhbClient.ReceiptQuery query = invocation.getArgument(1);
            assertThat(query.createdWindow()).isNull();
            assertThat(query.updatedFrom()).isNull();
            return new DhbClient.Page<>(query.page(), 0, List.of());
        });
        when(client.getPayments(any(), any())).thenAnswer(invocation -> {
            DhbClient.PaymentQuery query = invocation.getArgument(1);
            assertThat(query.createdWindow()).isNull();
            return new DhbClient.Page<>(query.page(), 0, List.of());
        });
        seedOrderProjectionMappings(tenant, connector.id());
        CallerIdentity caller = new CallerIdentity("TENANT", actor, tenant, actor, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("integration:dhb:write"));
        DhbOrderSyncService worker = new DhbOrderSyncService(syncStore, client,
                new FakeOrderProjectionClient(), new FakeIamDhbStaffSyncClient());

        var result = worker.runOrderPull(caller, task.id(), null);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.windowFrom()).isNull();
        assertThat(result.windowTo()).isNull();
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(checkpoint(tenant, task.id())).isNull();
    }

    @Test
    void keepsRawLandingAndLookupIsolatedByConnectorForTheSamePayload() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ConnectorView firstConnector = store.createConnector(tenant, actor,
                new ConnectorCommand("DHB_RAW_FIRST", "订货宝一",
                        "https://first.dhb.example", "env://DHB_RAW_FIRST", "ACTIVE", 0));
        ConnectorView secondConnector = store.createConnector(tenant, actor,
                new ConnectorCommand("DHB_RAW_SECOND", "订货宝二",
                        "https://second.dhb.example", "env://DHB_RAW_SECOND", "ACTIVE", 0));
        var firstTask = orderTaskFor(tenant, firstConnector.id());
        var secondTask = orderTaskFor(tenant, secondConnector.id());
        Instant from = Instant.parse("2026-08-04T00:00:00Z");
        Instant to = Instant.parse("2026-08-04T01:00:00Z");
        DhbClient.OrderSummary source = order("DHB-SAME", "12.50");
        DhbClient client = mock(DhbClient.class);
        when(client.getOrders(any(), any())).thenAnswer(invocation -> {
            DhbClient.OrderQuery query = invocation.getArgument(1);
            return new DhbClient.Page<>(query.page(), 1,
                    query.page().begin() == 0 ? List.of(source) : List.of());
        });
        when(client.getOrderContent(any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> orderDetail(invocation.getArgument(1)));
        stubEmptyDependentOrderFeeds(client);
        seedOrderProjectionMappings(tenant, firstConnector.id());
        seedOrderProjectionMappings(tenant, secondConnector.id());
        CallerIdentity caller = new CallerIdentity("TENANT", actor, tenant, actor, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("integration:dhb:write"));
        DhbOrderSyncService worker = new DhbOrderSyncService(
                syncStore, client, new FakeOrderProjectionClient(),
                new FakeIamDhbStaffSyncClient());

        var first = worker.runOrderPull(caller, firstTask.id(), new SyncRunCommand(from, to, 1));
        var second = worker.runOrderPull(caller, secondTask.id(), new SyncRunCommand(from, to, 1));

        assertThat(first.acceptedCount()).isEqualTo(1);
        assertThat(second.acceptedCount()).isEqualTo(1);
        assertThat(rawLandingCount(tenant, "DHB-SAME")).isEqualTo(4L);
        assertThat(rawLandingConnectors(tenant, "DHB-SAME")).hasSize(2);
        assertThat(orderMirrorCount(tenant, "DHB-SAME")).isEqualTo(2L);
        assertThat(orderMirrorConnectors(tenant, "DHB-SAME")).hasSize(2);
        assertThat(outboxEventCount(tenant, "DHB_ORDER_MIRROR_UPSERTED")).isEqualTo(2L);
    }

    @Test
    void projectsOrderWhenDhbRepeatsTheSameSkuLine() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ConnectorView connector = store.createConnector(tenant, actor,
                new ConnectorCommand("DHB_DUPLICATE_LINE", "订货宝重复明细",
                        "https://duplicate-line.dhb.example", "env://DHB_DUPLICATE_LINE", "ACTIVE", 0));
        var task = orderTaskFor(tenant, connector.id());
        Instant from = Instant.parse("2026-08-04T00:00:00Z");
        Instant to = Instant.parse("2026-08-04T01:00:00Z");
        DhbClient.OrderSummary source = order("DHB-DUP-LINE", "56.00");
        DhbClient client = mock(DhbClient.class);
        when(client.getOrders(any(), any())).thenAnswer(invocation -> {
            DhbClient.OrderQuery query = invocation.getArgument(1);
            return new DhbClient.Page<>(query.page(), 1,
                    query.page().begin() == 0 ? List.of(source) : List.of());
        });
        when(client.getOrderContent(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(orderDetail("DHB-DUP-LINE", List.of(
                        orderProductRow("PROD-1", "SKU-1", "2", "10.00", "箱"),
                        orderProductRow("PROD-1", "SKU-1", "3", "12.00", "箱"))));
        stubEmptyDependentOrderFeeds(client);
        seedOrderProjectionMappings(tenant, connector.id());
        CallerIdentity caller = new CallerIdentity("TENANT", actor, tenant, actor, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("integration:dhb:write"));
        FakeOrderProjectionClient orderProjection = new FakeOrderProjectionClient();
        DhbOrderSyncService worker = new DhbOrderSyncService(syncStore, client, orderProjection,
                new FakeIamDhbStaffSyncClient());

        var result = worker.runOrderPull(caller, task.id(), new SyncRunCommand(from, to, 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.acceptedCount()).isEqualTo(1);
        SalesOrderDetailView projected = orderProjection.rows.values().iterator().next();
        assertThat(projected.lines()).hasSize(1);
        assertThat(projected.lines().getFirst().quantity()).isEqualByComparingTo("5");
        assertThat(projected.lines().getFirst().unitPrice()).isEqualByComparingTo("11.200000");
    }

    @Test
    void resolvesOrderProductSkuByMappedProductSourceIdWhenLineOnlyCarriesProductCode() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ConnectorView connector = store.createConnector(tenant, actor,
                new ConnectorCommand("DHB_PRODUCT_CODE_ONLY", "订货宝商品编码明细",
                        "https://product-code.dhb.example", "env://DHB_PRODUCT_CODE_ONLY", "ACTIVE", 0));
        var task = orderTaskFor(tenant, connector.id());
        Instant from = Instant.parse("2026-08-04T00:00:00Z");
        Instant to = Instant.parse("2026-08-04T01:00:00Z");
        DhbClient.OrderSummary source = order("DHB-CODE-ONLY", "20.00");
        DhbClient client = mock(DhbClient.class);
        when(client.getOrders(any(), any())).thenAnswer(invocation -> {
            DhbClient.OrderQuery query = invocation.getArgument(1);
            return new DhbClient.Page<>(query.page(), 1,
                    query.page().begin() == 0 ? List.of(source) : List.of());
        });
        when(client.getOrderContent(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(orderDetail("DHB-CODE-ONLY", List.of(Map.of(
                        "Coding", "P-1",
                        "OptionsGoodsNo", "SKU-1",
                        "Name", "酸麻粉面菜蛋",
                        "multiName", "箱装",
                        "ContentNumber", "2",
                        "ContentPrice", "10.00",
                        "Units", "箱"))));
        stubEmptyDependentOrderFeeds(client);
        seedMapping(tenant, connector.id(), "CUSTOMER", "CLIENT-1", "上海静安店",
                "CRM", "CUSTOMER", 1L, "CUS-1");
        seedMapping(tenant, connector.id(), "PRODUCT_SPU", "PROD-1", "P-1",
                "ERP", "PRODUCT", 10L, "SP202608220001");
        seedMapping(tenant, connector.id(), "PRODUCT_SKU", "PROD-1::SKU-1", "SKU-1",
                "ERP", "PRODUCT_VARIANT", 11L, "SK202608220001");
        CallerIdentity caller = new CallerIdentity("TENANT", actor, tenant, actor, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("integration:dhb:write"));
        FakeOrderProjectionClient orderProjection = new FakeOrderProjectionClient();
        DhbOrderSyncService worker = new DhbOrderSyncService(syncStore, client, orderProjection,
                new FakeIamDhbStaffSyncClient());

        var result = worker.runOrderPull(caller, task.id(), new SyncRunCommand(from, to, 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.acceptedCount()).isEqualTo(1);
        SalesOrderDetailView projected = orderProjection.rows.values().iterator().next();
        assertThat(projected.lines().getFirst().productCodeSnapshot()).isEqualTo("SP202608220001");
        assertThat(projected.lines().getFirst().skuCodeSnapshot()).isEqualTo("SK202608220001");
    }

    private static DhbClient.OrderSummary order(String orderNumber, String amount) {
        Instant updatedAt = Instant.parse("2026-08-04T00:30:00Z");
        return new DhbClient.OrderSummary(orderNumber, orderNumber, "WAIT_AUDIT",
                new BigDecimal(amount), updatedAt.minusSeconds(60), updatedAt,
                "CLIENT-1", "UNPAID",
                Map.of("OrderSN", orderNumber, "OrderTotal", amount, "OrderStatus", "WAIT_AUDIT"));
    }

    private static void stubEmptyDependentOrderFeeds(DhbClient client) {
        when(client.getShipments(any(), any())).thenAnswer(invocation -> {
            DhbClient.ShipmentQuery query = invocation.getArgument(1);
            return new DhbClient.Page<>(query.page(), 0, List.of());
        });
        when(client.getReceipts(any(), any())).thenAnswer(invocation -> {
            DhbClient.ReceiptQuery query = invocation.getArgument(1);
            return new DhbClient.Page<>(query.page(), 0, List.of());
        });
        when(client.getPayments(any(), any())).thenAnswer(invocation -> {
            DhbClient.PaymentQuery query = invocation.getArgument(1);
            return new DhbClient.Page<>(query.page(), 0, List.of());
        });
    }

    private static DhbClient.OrderDetail orderDetail(String orderNumber) {
        return orderDetail(orderNumber, List.of(orderProductRow("PROD-1", "SKU-1", "2", "10.00", "箱")));
    }

    private static DhbClient.OrderDetail orderDetail(String orderNumber, List<Map<String, Object>> products) {
        return new DhbClient.OrderDetail(orderNumber, "WAIT_AUDIT", new BigDecimal("12.50"),
                Map.of(
                        "OrderSN", orderNumber,
                        "OrderStatus", "WAIT_AUDIT",
                        "OrderDate", "2026-08-04 08:30:00",
                        "ClientNO", "CLIENT-1",
                        "ClientName", "上海静安店",
                        "SalesmanName", "刘彦",
                        "OrderReceiveName", "张三",
                        "OrderReceivePhone", "13800000000",
                        "OrderProduct", products));
    }

    private static Map<String, Object> orderProductRow(
            String productSourceId, String skuSourceId, String quantity, String unitPrice, String unit) {
        return Map.of(
                "Guid", productSourceId,
                "OptionsGoodsNo", skuSourceId,
                "Name", "酸麻粉面菜蛋",
                "Coding", "P-1",
                "multiName", "箱装",
                "ContentNumber", quantity,
                "ContentPrice", unitPrice,
                "Units", unit);
    }

    private void seedOrderProjectionMappings(UUID tenantId, UUID connectorId) {
        seedMapping(tenantId, connectorId, "CUSTOMER", "CLIENT-1", "上海静安店",
                "CRM", "CUSTOMER", 1L, "CUS-1");
        seedMapping(tenantId, connectorId, "PRODUCT_SPU", "PROD-1", "酸麻粉面菜蛋",
                "ERP", "PRODUCT", 10L, "P-1");
        seedMapping(tenantId, connectorId, "PRODUCT_SKU", "PROD-1::SKU-1", "箱装",
                "ERP", "PRODUCT_VARIANT", 11L, "SKU-1");
    }

    private void seedMapping(UUID tenantId, UUID connectorId, String sourceObjectType,
                             String sourceObjectId, String sourceObjectNo,
                             String internalDomain, String internalObjectType,
                             Long internalObjectId, String internalObjectNo) {
        ExternalObjectMappingEntity row = new ExternalObjectMappingEntity();
        row.id = IntegrationUuidCodec.encode(UUID.randomUUID());
        row.tenantId = IntegrationUuidCodec.encode(tenantId);
        row.connectorId = IntegrationUuidCodec.encode(connectorId);
        row.sourceSystem = "DHB";
        row.sourceObjectType = sourceObjectType;
        row.sourceObjectId = sourceObjectId;
        row.sourceObjectNo = sourceObjectNo;
        row.internalDomain = internalDomain;
        row.internalObjectType = internalObjectType;
        row.internalObjectId = internalObjectId;
        row.internalObjectNo = internalObjectNo;
        row.mappingStatus = "ACTIVE";
        row.lastSeenAt = LocalDateTime.now(ZoneOffset.UTC);
        row.version = 0L;
        row.createdAt = row.lastSeenAt;
        row.updatedAt = row.lastSeenAt;
        externalObjectMappingMapper.insert(row);
    }

    private static final class FakeOrderProjectionClient implements OrderSalesOrderProjectionClient {
        private final Map<Long, SalesOrderDetailView> rows = new java.util.LinkedHashMap<>();
        private final Map<Long, SalesPaymentRecordDetailView> payments = new java.util.LinkedHashMap<>();
        private final Map<Long, FundDocumentDetailView> fundDocuments = new java.util.LinkedHashMap<>();
        private final Map<Long, SalesRefundRecordDetailView> refunds = new java.util.LinkedHashMap<>();
        private final Map<Long, SalesShipmentDetailView> shipments = new java.util.LinkedHashMap<>();
        private long nextId = 100;
        private long nextPaymentId = 1000;
        private long nextFundDocumentId = 1200;
        private long nextRefundId = 1500;
        private long nextShipmentId = 2000;
        private int created;
        private int paymentCreated;
        private int fundDocumentCreated;
        private int refundCreated;
        private int shipmentCreated;

        @Override
        public SalesOrderDetailView salesOrder(CallerIdentity caller, Long id) {
            return Optional.ofNullable(rows.get(id))
                    .orElseThrow(() -> new IllegalArgumentException("sales order not found"));
        }

        @Override
        public SalesOrderDetailView createSalesOrder(CallerIdentity caller, SalesOrderCommand command) {
            created++;
            SalesOrderDetailView value = detail(nextId++, "DD2026080400" + created,
                    command, "DRAFT", 1);
            rows.put(value.id(), value);
            return value;
        }

        @Override
        public SalesOrderDetailView updateSalesOrder(CallerIdentity caller, Long id,
                                                     SalesOrderCommand command) {
            SalesOrderDetailView value = detail(id, rows.get(id).orderNo(), command,
                    Boolean.TRUE.equals(command.submit()) ? "SUBMITTED" : "DRAFT",
                    command.revision() + 1);
            rows.put(id, value);
            return value;
        }

        @Override
        public SalesOrderDetailView cancelSalesOrder(CallerIdentity caller, Long id, int revision) {
            SalesOrderDetailView current = salesOrder(caller, id);
            SalesOrderDetailView cancelled = detail(id, current.orderNo(), command(current),
                    "CANCELLED", revision + 1);
            rows.put(id, cancelled);
            return cancelled;
        }

        @Override
        public SalesPaymentRecordDetailView salesPayment(CallerIdentity caller, Long id) {
            return Optional.ofNullable(payments.get(id))
                    .orElseThrow(() -> new IllegalArgumentException("sales payment not found"));
        }

        @Override
        public SalesPaymentRecordDetailView createSalesPayment(
                CallerIdentity caller, SalesPaymentRecordCommand command) {
            paymentCreated++;
            SalesPaymentRecordDetailView value = payment(nextPaymentId++,
                    "PAY2026080400" + paymentCreated, command, 1);
            payments.put(value.id(), value);
            return value;
        }

        @Override
        public SalesPaymentRecordDetailView updateSalesPayment(
                CallerIdentity caller, Long id, SalesPaymentRecordCommand command) {
            SalesPaymentRecordDetailView value = payment(id, payments.get(id).paymentNo(),
                    command, command.revision() + 1);
            payments.put(id, value);
            return value;
        }

        @Override
        public FundDocumentDetailView fundDocument(CallerIdentity caller, Long id) {
            return Optional.ofNullable(fundDocuments.get(id))
                    .orElseThrow(() -> new IllegalArgumentException("fund document not found"));
        }

        @Override
        public FundDocumentDetailView createFundDocument(
                CallerIdentity caller, FundDocumentCommand command) {
            fundDocumentCreated++;
            FundDocumentDetailView value = fundDocument(nextFundDocumentId++,
                    "FD2026080400" + fundDocumentCreated, command, 1);
            fundDocuments.put(value.id(), value);
            return value;
        }

        @Override
        public FundDocumentDetailView updateFundDocument(
                CallerIdentity caller, Long id, FundDocumentCommand command) {
            FundDocumentDetailView value = fundDocument(id, fundDocuments.get(id).documentNo(),
                    command, command.revision() + 1);
            fundDocuments.put(id, value);
            return value;
        }

        @Override
        public SalesRefundRecordDetailView salesRefund(CallerIdentity caller, Long id) {
            return Optional.ofNullable(refunds.get(id))
                    .orElseThrow(() -> new IllegalArgumentException("sales refund not found"));
        }

        @Override
        public SalesRefundRecordDetailView createSalesRefund(
                CallerIdentity caller, SalesRefundRecordCommand command) {
            refundCreated++;
            SalesRefundRecordDetailView value = refund(nextRefundId++,
                    "TK2026080400" + refundCreated, command, 1);
            refunds.put(value.id(), value);
            return value;
        }

        @Override
        public SalesRefundRecordDetailView updateSalesRefund(
                CallerIdentity caller, Long id, SalesRefundRecordCommand command) {
            SalesRefundRecordDetailView value = refund(id, refunds.get(id).refundNo(),
                    command, command.revision() + 1);
            refunds.put(id, value);
            return value;
        }

        @Override
        public SalesShipmentDetailView salesShipment(CallerIdentity caller, Long id) {
            return Optional.ofNullable(shipments.get(id))
                    .orElseThrow(() -> new IllegalArgumentException("sales shipment not found"));
        }

        @Override
        public SalesShipmentDetailView createSalesShipment(
                CallerIdentity caller, SalesShipmentCommand command) {
            shipmentCreated++;
            SalesShipmentDetailView value = shipment(nextShipmentId++,
                    "FH2026080400" + shipmentCreated, command, 1);
            shipments.put(value.id(), value);
            return value;
        }

        @Override
        public SalesShipmentDetailView updateSalesShipment(
                CallerIdentity caller, Long id, SalesShipmentCommand command) {
            SalesShipmentDetailView value = shipment(id, shipments.get(id).shipmentNo(),
                    command, command.revision() + 1);
            shipments.put(id, value);
            return value;
        }

        private static SalesOrderDetailView detail(Long id, String orderNo,
                                                   SalesOrderCommand command,
                                                   String status, int revision) {
            BigDecimal totalQuantity = command.lines().stream()
                    .map(SalesOrderLineCommand::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal payableAmount = command.lines().stream()
                    .map(line -> line.quantity().multiply(line.unitPrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<SalesOrderLineView> lines = command.lines().stream()
                    .map(line -> new SalesOrderLineView(1L, 1, line.productId(),
                            line.productVariantId(), line.productCodeSnapshot(),
                            line.skuCodeSnapshot(), line.productNameSnapshot(),
                            line.specificationSnapshot(), line.unitCode(), line.quantity(),
                            line.unitPrice(), line.discountRate(), line.discountAmount(),
                            line.quantity().multiply(line.unitPrice()), line.remark()))
                    .toList();
            return new SalesOrderDetailView(id, orderNo, command.customerId(),
                    command.customerCodeSnapshot(), command.customerNameSnapshot(),
                    command.contactNameSnapshot(), command.contactPhoneSnapshot(),
                    command.regionCode(), command.ownerSalesUserId(), command.ownerSalesName(),
                    command.ownerStaffCode(), command.ownerStaffNameSnapshot(),
                    command.orderDate(), status, command.orderTypeCode(),
                    command.paymentMethodCode(), "UNPAID", "PENDING", totalQuantity,
                    payableAmount, command.discountRate(), command.discountAmount(),
                    payableAmount, BigDecimal.ZERO, payableAmount, command.remark(), revision,
                    "TEST", Instant.now(), "TEST", Instant.now(), lines);
        }

        private static SalesOrderCommand command(SalesOrderDetailView source) {
            return new SalesOrderCommand(source.customerId(), source.customerCodeSnapshot(),
                    source.customerNameSnapshot(), source.contactNameSnapshot(),
                    source.contactPhoneSnapshot(), source.regionCode(), source.ownerSalesUserId(),
                    source.ownerSalesName(), source.ownerStaffCode(), source.ownerStaffNameSnapshot(),
                    source.orderDate(), source.orderTypeCode(),
                    source.paymentMethodCode(), source.discountRate(), source.discountAmount(),
                    source.remark(), source.lines().stream()
                    .map(line -> new SalesOrderLineCommand(line.productId(), line.productVariantId(),
                            line.productCodeSnapshot(), line.skuCodeSnapshot(),
                            line.productNameSnapshot(), line.specificationSnapshot(),
                            line.unitCode(), line.quantity(), line.unitPrice(),
                            line.discountRate(), line.discountAmount(), line.remark()))
                    .toList(), true, source.revision());
        }

        private static SalesPaymentRecordDetailView payment(
                Long id, String paymentNo, SalesPaymentRecordCommand command, int revision) {
            return new SalesPaymentRecordDetailView(id, paymentNo, command.orderId(),
                    "DD202608040001", 1L, "KH202608040001", "测试客户",
                    command.collectorStaffCode(), command.collectorNameSnapshot(),
                    command.paymentTime(), command.paymentMethodCode(), command.paidAmount(),
                    command.voucherKeys(), command.remark(), revision, "TEST", Instant.now(),
                    "TEST", Instant.now());
        }

        private static FundDocumentDetailView fundDocument(
                Long id, String documentNo, FundDocumentCommand command, int revision) {
            return new FundDocumentDetailView(id, documentNo, command.directionCode(),
                    command.relatedOrderId(), command.salesOrderNoSnapshot(), command.customerId(),
                    command.customerCodeSnapshot(), command.customerNameSnapshot(),
                    command.counterpartyTypeCode(), command.counterpartyCodeSnapshot(),
                    command.counterpartyNameSnapshot(), command.handlerStaffCode(),
                    command.handlerStaffNameSnapshot(), command.occurredTime(),
                    command.settlementMethodCode(), command.businessTypeCode(),
                    command.documentStatusCode(), command.amount(), command.voucherKeys(),
                    command.remark(), revision, "TEST", Instant.now(), "TEST", Instant.now());
        }

        private static SalesRefundRecordDetailView refund(
                Long id, String refundNo, SalesRefundRecordCommand command, int revision) {
            return new SalesRefundRecordDetailView(id, refundNo, command.orderId(),
                    "DD202608040001", 1L, "KH202608040001", "测试客户",
                    command.refundStaffCode(), command.refundStaffNameSnapshot(),
                    command.refundTime(), command.refundMethodCode(), command.refundStatusCode(),
                    command.refundAmount(), command.voucherKeys(), command.remark(), revision,
                    "TEST", Instant.now(), "TEST", Instant.now());
        }

        private static SalesShipmentDetailView shipment(
                Long id, String shipmentNo, SalesShipmentCommand command, int revision) {
            BigDecimal totalQuantity = command.lines().stream()
                    .map(line -> line.shippedQuantity() == null ? BigDecimal.ZERO : line.shippedQuantity())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<SalesShipmentLineView> lines = command.lines().stream()
                    .map(line -> new SalesShipmentLineView(1L, line.salesOrderLineId(), 1,
                            line.productId(), line.productVariantId(), line.productCodeSnapshot(),
                            line.skuCodeSnapshot(), line.productNameSnapshot(),
                            line.specificationSnapshot(), line.unitCode(), line.shippedQuantity(),
                            line.remark()))
                    .toList();
            return new SalesShipmentDetailView(id, shipmentNo, command.salesOrderId(),
                    "DD202608040001", 1L, "KH202608040001", "测试客户", "13800000000",
                    null, null, command.warehouseId(), command.stockOutOrderId(), command.stockOutNo(),
                    command.shipmentStatusCode(), command.logisticsCompany(), command.trackingNo(),
                    command.shipTime(), totalQuantity, command.remark(), revision, "TEST", Instant.now(),
                    "TEST", Instant.now(), lines);
        }
    }

    private static final class FakeIamDhbStaffSyncClient implements IamDhbStaffSyncClient {
        @Override
        public StaffSyncResult sync(CallerIdentity caller, List<DhbStaffRow> rows) {
            int received = rows == null ? 0 : rows.size();
            return new StaffSyncResult(received, 0, 0, received, 0, List.of());
        }

        @Override
        public List<ResolvedStaff> resolve(CallerIdentity caller, String sourceTenantKey,
                                           List<String> sourceStaffIds,
                                           List<String> sourceStaffNames) {
            if (sourceStaffNames != null && sourceStaffNames.contains("刘彦")) {
                return List.of(new ResolvedStaff(sourceTenantKey, null,
                        UUID.fromString("019fb100-0000-7000-8000-000000000901"),
                        "RY202608220001", "刘彦", null, null, null, null, null,
                        null, null, "ACTIVE", null, null, "PRESENT", Instant.now()));
            }
            return List.of();
        }
    }

    private SyncTaskView orderTaskFor(UUID tenantId, UUID connectorId) {
        return store.syncTasks(tenantId).stream()
                .filter(task -> connectorId.equals(task.connectorId()) && "ORDER".equals(task.objectType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("默认ORDER同步任务不存在"));
    }

    private UUID connectorIdFromTask(UUID tenantId, String taskCode) {
        IntegrationSyncTaskEntity row = first(syncTaskMapper.selectList(
                Wrappers.<IntegrationSyncTaskEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("task_code", taskCode)
                        .last("LIMIT 1")));
        return row == null ? null : IntegrationUuidCodec.decode(row.connectorId);
    }

    private UUID connectorIdFromRawLanding(UUID tenantId, String sourceId) {
        IntegrationRawLandingEntity row = first(rawLandingMapper.selectList(
                Wrappers.<IntegrationRawLandingEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("source_id", sourceId)
                        .last("LIMIT 1")));
        return row == null ? null : IntegrationUuidCodec.decode(row.connectorId);
    }

    private UUID connectorIdFromOrderMirror(UUID tenantId, String sourceOrderId) {
        IntegrationOrderMirrorEntity row = first(orderMirrorMapper.selectList(
                Wrappers.<IntegrationOrderMirrorEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("source_order_id", sourceOrderId)
                        .last("LIMIT 1")));
        return row == null ? null : IntegrationUuidCodec.decode(row.connectorId);
    }

    private DhbConnectorEntity connectorRow(UUID tenantId, UUID connectorId) {
        return first(connectorMapper.selectList(Wrappers.<DhbConnectorEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(connectorId))
                .last("LIMIT 1")));
    }

    private void updateTask(UUID taskId, String column, Object value) {
        syncTaskMapper.update(null, Wrappers.<IntegrationSyncTaskEntity>update()
                .set(column, value)
                .eq("id", bin(taskId)));
    }

    private long rawLandingCount(UUID tenantId) {
        return rawLandingMapper.selectCount(Wrappers.<IntegrationRawLandingEntity>query()
                .eq("tenant_id", bin(tenantId)));
    }

    private long rawLandingCount(UUID tenantId, String sourceId) {
        return rawLandingMapper.selectCount(Wrappers.<IntegrationRawLandingEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("source_id", sourceId));
    }

    private Set<UUID> rawLandingConnectors(UUID tenantId, String sourceId) {
        return rawLandingMapper.selectList(Wrappers.<IntegrationRawLandingEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("source_id", sourceId))
                .stream().map(row -> IntegrationUuidCodec.decode(row.connectorId))
                .collect(java.util.stream.Collectors.toSet());
    }

    private long orderMirrorCount(UUID tenantId) {
        return orderMirrorMapper.selectCount(Wrappers.<IntegrationOrderMirrorEntity>query()
                .eq("tenant_id", bin(tenantId)));
    }

    private long orderMirrorCount(UUID tenantId, String sourceOrderId) {
        return orderMirrorMapper.selectCount(Wrappers.<IntegrationOrderMirrorEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("source_order_id", sourceOrderId));
    }

    private Set<UUID> orderMirrorConnectors(UUID tenantId, String sourceOrderId) {
        return orderMirrorMapper.selectList(Wrappers.<IntegrationOrderMirrorEntity>query()
                        .eq("tenant_id", bin(tenantId))
                        .eq("source_order_id", sourceOrderId))
                .stream().map(row -> IntegrationUuidCodec.decode(row.connectorId))
                .collect(java.util.stream.Collectors.toSet());
    }

    private long outboxEventCount(UUID tenantId) {
        return outboxEventMapper.selectCount(Wrappers.<IntegrationOutboxEventEntity>query()
                .eq("tenant_id", bin(tenantId)));
    }

    private long outboxEventCount(UUID tenantId, String eventType) {
        return outboxEventMapper.selectCount(Wrappers.<IntegrationOutboxEventEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("event_type", eventType));
    }

    private long externalMappingCount(UUID tenantId, String sourceObjectType) {
        return externalObjectMappingMapper.selectCount(Wrappers.<ExternalObjectMappingEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("source_object_type", sourceObjectType));
    }

    private IntegrationSyncCheckpointEntity checkpoint(UUID tenantId, UUID taskId) {
        return first(checkpointMapper.selectList(Wrappers.<IntegrationSyncCheckpointEntity>query()
                .eq("tenant_id", bin(tenantId))
                .eq("task_id", bin(taskId))
                .last("LIMIT 1")));
    }

    private static byte[] bin(UUID value) {
        return IntegrationUuidCodec.encode(value);
    }

    private static <T> T first(List<T> rows) {
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }
}
