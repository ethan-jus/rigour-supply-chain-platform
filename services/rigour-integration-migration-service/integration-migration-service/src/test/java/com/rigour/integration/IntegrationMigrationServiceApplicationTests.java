package com.rigour.integration;

import com.rigour.integration.application.port.out.DinghuobaoIntegrationStore;
import com.rigour.integration.application.port.out.DinghuobaoClient.ConnectionTestResult;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.ConnectorCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.ConnectorView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.FieldMappingCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncTaskCommand;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DinghuobaoIntegrationStore store;

    @Test
    void contextLoadsAndMigratesIntegrationSchema() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class)).contains("1", "2");
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
                "integration_domain_ownership");
    }

    @Test
    void persistsConnectorTaskAndFieldMappingWithTenantBoundary() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        ConnectorView connector = store.createConnector(tenantA, actor,
                new ConnectorCommand("DINGHUOBAO_MAIN", "订货宝主连接",
                        "https://open.dinghuobao.example", "env://DHB_TEST", "ACTIVE", 0));
        store.createSyncTask(tenantA, actor,
                new SyncTaskCommand(connector.id(), "ORDER_PULL", "ORDER", "IDLE", null, 0));
        store.recordConnectionTest(tenantA, actor, connector.id(),
                ConnectionTestResult.failure("DINGHUOBAO_AUTH_FAILED", "认证失败"));
        store.saveFieldMapping(tenantA, actor, null,
                new FieldMappingCommand(connector.id(), "orderId", "sourceOrderId", "DIRECT", true, 0));

        assertThat(store.connectors(tenantA)).extracting(ConnectorView::code).contains("DINGHUOBAO_MAIN");
        assertThat(store.connectors(tenantB)).isEmpty();
        assertThat(store.syncTasks(tenantA)).hasSize(1);
        assertThat(store.fieldMappings(tenantA, connector.id())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT credential_status FROM integration_dinghuobao_connector
                 WHERE tenant_id=? AND id=?
                """, String.class, IntegrationUuidCodec.encode(tenantA),
                IntegrationUuidCodec.encode(connector.id())))
                .isEqualTo("INVALID");
        assertThatThrownBy(() -> store.fieldMappings(tenantB, connector.id()))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
    }
}
