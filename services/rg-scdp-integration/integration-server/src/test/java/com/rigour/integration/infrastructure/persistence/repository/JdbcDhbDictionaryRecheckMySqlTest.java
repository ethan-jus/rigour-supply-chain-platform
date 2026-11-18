package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 只在单连接临时表中验证 MySQL JSON 和 UUID 条件，不修改已有业务表。 */
@EnabledIfEnvironmentVariable(named = "DHB_MYSQL_TEST_URL", matches = ".+")
class JdbcDhbDictionaryRecheckMySqlTest {
    private final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID();
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate db;
    private JdbcDhbIncrementalWorkStore store;

    @BeforeEach
    void setup() throws Exception {
        var connection = DriverManager.getConnection(System.getenv("DHB_MYSQL_TEST_URL"),
                System.getenv("DHB_MYSQL_TEST_USER"), System.getenv("DHB_MYSQL_TEST_PASSWORD"));
        dataSource = new SingleConnectionDataSource(connection, true);
        db = new JdbcTemplate(dataSource);
        db.execute("CREATE TEMPORARY TABLE integration_dhb_incremental_work (tenant_id CHAR(36),"
                + "connector_id CHAR(36),object_type VARCHAR(24),source_id VARCHAR(128),"
                + "applied INT,retry_policy VARCHAR(16),retry_at DATETIME(6))");
        db.execute("CREATE TEMPORARY TABLE integration_raw_landing (tenant_id BINARY(16),"
                + "connector_id BINARY(16),source_object_type VARCHAR(40),source_id VARCHAR(128),payload_json JSON)");
        store = new JdbcDhbIncrementalWorkStore(db);
    }

    @AfterEach
    void close() {
        if (dataSource != null) dataSource.destroy();
    }

    @Test
    void queuesMatchingOriginalShipmentsAcrossTenantConnectorsWithoutTouchingOthers() {
        var otherTenant = UUID.randomUUID();
        var otherConnector = UUID.randomUUID();
        work(tenant, connector, "DETAIL", "SHIPMENT", "AUTO");
        raw(tenant, connector, "DETAIL", "detail", "85984");
        work(tenant, connector, "LIST", "SHIPMENT", "AUTO");
        raw(tenant, connector, "LIST", "list", "85984");
        work(tenant, otherConnector, "OWN_OTHER_CONNECTOR", "SHIPMENT", "AUTO");
        raw(tenant, otherConnector, "OWN_OTHER_CONNECTOR", "detail", "85984");
        work(otherTenant, connector, "OTHER_TENANT", "SHIPMENT", "AUTO");
        raw(otherTenant, connector, "OTHER_TENANT", "detail", "85984");
        work(tenant, connector, "OTHER_VALUE", "SHIPMENT", "AUTO");
        raw(tenant, connector, "OTHER_VALUE", "detail", "85985");
        work(tenant, connector, "MANUAL", "SHIPMENT", "MANUAL");
        raw(tenant, connector, "MANUAL", "detail", "85984");
        work(tenant, connector, "ORDER", "SALES_ORDER", "AUTO");
        raw(tenant, connector, "ORDER", "detail", "85984");
        work(tenant, connector, "CROSS_RAW", "SHIPMENT", "AUTO");
        raw(tenant, otherConnector, "CROSS_RAW", "detail", "85984");

        assertThat(store.recheckShipmentDictionary(tenant, "shipment.deliveryMode", "85984",
                Instant.parse("2026-11-18T03:00:00Z"))).isEqualTo(3);
        assertThat(db.queryForList("SELECT source_id FROM integration_dhb_incremental_work"
                + " WHERE applied=0 ORDER BY source_id", String.class))
                .containsExactly("DETAIL", "LIST", "OWN_OTHER_CONNECTOR");
        assertThat(store.recheckShipmentDictionary(tenant, "shipment.deliveryMode", "x' OR 1=1 --",
                Instant.now())).isZero();
        assertThatThrownBy(() -> store.recheckShipmentDictionary(tenant, "arbitrary.column", "85984",
                Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carrierMappingAlsoMatchesOriginalCarrierName() {
        work(tenant, connector, "CARRIER", "SHIPMENT", "AUTO");
        db.update("INSERT INTO integration_raw_landing VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),"
                + "'SHIPMENT_CONTENT','CARRIER',?)", tenant.toString(), connector.toString(),
                "{\"detail\":{\"logistics_name\":\"顺丰速运/顺丰冷链\"}}");
        assertThat(store.recheckShipmentDictionary(tenant, "shipment.logistics", "顺丰速运/顺丰冷链",
                Instant.now())).isOne();
    }

    private void work(UUID t, UUID c, String id, String type, String policy) {
        db.update("INSERT INTO integration_dhb_incremental_work VALUES(?,?,?,?,1,?,NULL)",
                t.toString(), c.toString(), type, id, policy);
    }

    private void raw(UUID t, UUID c, String id, String part, String value) {
        db.update("INSERT INTO integration_raw_landing VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),"
                + "'SHIPMENT_CONTENT',?,?)", t.toString(), c.toString(), id,
                "{\"" + part + "\":{\"distribution_mode_id\":\"" + value + "\"}}");
    }
}
