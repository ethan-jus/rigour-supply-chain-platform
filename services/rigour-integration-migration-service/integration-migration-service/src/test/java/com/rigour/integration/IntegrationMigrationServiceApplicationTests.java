package com.rigour.integration;

import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.application.service.dhb.DhbOrderSyncService;
import com.rigour.integration.infrastructure.media.ProductMediaSyncWorker;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        registry.add("rigour.integration.product-media.cos.region", () -> "ap-beijing");
        registry.add("rigour.integration.product-media.cos.bucket", () -> "rigour-test-1250000000");
        registry.add("rigour.integration.product-media.cos.secret-id", () -> "test-secret-id");
        registry.add("rigour.integration.product-media.cos.secret-key", () -> "test-secret-key");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DhbIntegrationStore store;

    @Autowired
    private DhbSyncStore syncStore;

    @MockitoBean
    private ProductMediaSyncWorker productMediaSyncWorker;

    @Test
    void contextLoadsAndMigratesIntegrationSchema() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class)).contains("1", "2", "3", "4");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name LIKE 'integration\\_%'
                """, Integer.class)).isGreaterThanOrEqualTo(12);
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name LIKE 'integration\\_%'
                """, String.class)).contains(
                "integration_sync_run",
                "integration_sync_checkpoint",
                "integration_dead_letter",
                "integration_outbox_event",
                "integration_reconciliation_case",
                "integration_domain_ownership",
                "integration_dhb_connector");
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
                "DHB_CRM_MASTER_DEFAULT");
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
        assertThat(jdbcTemplate.queryForObject("""
                SELECT credential_status FROM integration_dhb_connector
                 WHERE tenant_id=? AND id=?
                """, String.class, IntegrationUuidCodec.encode(tenantA),
                IntegrationUuidCodec.encode(connector.id())))
                .isEqualTo("INVALID");
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
        jdbcTemplate.update("UPDATE integration_sync_task SET enabled=0 WHERE id=?",
                IntegrationUuidCodec.encode(disabledTask.id()));

        ConnectorView productConnector = store.createConnector(tenantA, actor,
                new ConnectorCommand("DHB_DISCOVERY_PRODUCT", "商品连接",
                        "https://open.dhb.example", "env://DHB_DISCOVERY_PRODUCT", "ACTIVE", 0));
        var productOrderTask = orderTaskFor(tenantA, productConnector.id());
        jdbcTemplate.update("UPDATE integration_sync_task SET enabled=0 WHERE id=?",
                IntegrationUuidCodec.encode(productOrderTask.id()));
        var productTask = store.createSyncTask(tenantA, actor,
                new SyncTaskCommand(productConnector.id(), "PRODUCT_ACTIVE", "PRODUCT", "IDLE", null, 0));

        ConnectorView pausedConnector = store.createConnector(tenantA, actor,
                new ConnectorCommand("DHB_DISCOVERY_PAUSED", "暂停连接",
                        "https://open.dhb.example", "env://DHB_DISCOVERY_PAUSED", "ACTIVE", 0));
        var pausedTask = orderTaskFor(tenantA, pausedConnector.id());
        jdbcTemplate.update("UPDATE integration_sync_task SET task_status='PAUSED' WHERE id=?",
                IntegrationUuidCodec.encode(pausedTask.id()));

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

        CallerIdentity caller = new CallerIdentity("TENANT", actor, tenant, actor, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("integration:dhb:write"));
        DhbOrderSyncService worker = new DhbOrderSyncService(syncStore, client);

        var result = worker.runOrderPull(caller, task.id(), new SyncRunCommand(from, to, 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM integration_raw_landing WHERE tenant_id=?",
                Integer.class, IntegrationUuidCodec.encode(tenant))).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM integration_order_mirror WHERE tenant_id=?",
                Integer.class, IntegrationUuidCodec.encode(tenant))).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM integration_outbox_event WHERE tenant_id=?",
                Integer.class, IntegrationUuidCodec.encode(tenant))).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cursor_value FROM integration_sync_checkpoint WHERE tenant_id=? AND task_id=?",
                String.class, IntegrationUuidCodec.encode(tenant), IntegrationUuidCodec.encode(task.id())))
                .isEqualTo(to.toString());

        var repeated = worker.runOrderPull(caller, task.id(), new SyncRunCommand(from, to, 1));

        assertThat(repeated.status()).isEqualTo("SUCCEEDED");
        assertThat(repeated.acceptedCount()).isZero();
        assertThat(repeated.duplicateCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM integration_raw_landing WHERE tenant_id=?",
                Integer.class, IntegrationUuidCodec.encode(tenant))).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM integration_outbox_event WHERE tenant_id=?",
                Integer.class, IntegrationUuidCodec.encode(tenant))).isEqualTo(2);
    }

    private static DhbClient.OrderSummary order(String orderNumber, String amount) {
        Instant updatedAt = Instant.parse("2026-08-04T00:30:00Z");
        return new DhbClient.OrderSummary(orderNumber, orderNumber, "WAIT_AUDIT",
                new BigDecimal(amount), updatedAt.minusSeconds(60), updatedAt,
                "CLIENT-1", "UNPAID",
                Map.of("OrderSN", orderNumber, "OrderTotal", amount, "OrderStatus", "WAIT_AUDIT"));
    }

    private SyncTaskView orderTaskFor(UUID tenantId, UUID connectorId) {
        return store.syncTasks(tenantId).stream()
                .filter(task -> connectorId.equals(task.connectorId()) && "ORDER".equals(task.objectType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("默认ORDER同步任务不存在"));
    }
}
