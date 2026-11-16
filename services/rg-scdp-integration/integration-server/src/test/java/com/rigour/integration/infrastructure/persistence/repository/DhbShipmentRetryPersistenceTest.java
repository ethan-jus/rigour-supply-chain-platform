package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.application.port.out.DhbIncrementalWorkStore.Item;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.*;

class DhbShipmentRetryPersistenceTest {
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-13T10:00:00Z");
    JdbcTemplate jdbc;
    JdbcDhbIncrementalWorkStore store;

    @BeforeEach
    void setup() {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE integration_dhb_incremental_work(tenant_id VARCHAR(36),connector_id"
                    + " VARCHAR(36),object_type VARCHAR(32),source_id VARCHAR(128),fingerprint"
                    + " VARCHAR(64),payload VARCHAR(4000),source_updated_at TIMESTAMP,applied"
                    + " INT,retry_policy VARCHAR(16) DEFAULT 'AUTO',checked_at TIMESTAMP,retry_at"
                    + " TIMESTAMP,PRIMARY KEY(tenant_id,connector_id,object_type,source_id))");
        store = new JdbcDhbIncrementalWorkStore(jdbc);
        jdbc.update(
                "INSERT INTO integration_dhb_incremental_work"
                    + " VALUES(?,?,?,?,?,?,?,0,'AUTO',NULL,?)",
                tenant.toString(),
                connector.toString(),
                "SHIPMENT",
                "FH001",
                "old",
                "{}",
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    @Test
    void restartRetainsOnlyDueFailuresAndSuccessfulReplayRemovesPendingItem() {
        var item = store.find(tenant, connector, "SHIPMENT", "FH001");
        store.complete(tenant, connector, "SHIPMENT", item, false, now);
        var restarted = new JdbcDhbIncrementalWorkStore(jdbc);
        assertThat(restarted.pending(tenant, connector, "SHIPMENT", 100, now.plusSeconds(299)))
                .isEmpty();
        assertThat(restarted.pending(tenant, connector, "SHIPMENT", 100, now.plusSeconds(301)))
                .containsExactly(item);
        restarted.complete(tenant, connector, "SHIPMENT", item, true, now.plusSeconds(301));
        assertThat(restarted.pendingCount(tenant, connector, "SHIPMENT")).isZero();
    }

    @Test
    void oldWorkerCannotAcknowledgeChangedSourceVersion() {
        var original = store.find(tenant, connector, "SHIPMENT", "FH001");
        jdbc.update("UPDATE integration_dhb_incremental_work SET fingerprint='new'");
        store.complete(tenant, connector, "SHIPMENT", original, true, now);
        assertThat(store.pendingCount(tenant, connector, "SHIPMENT")).isEqualTo(1);
        assertThat(store.find(tenant, connector, "SHIPMENT", "FH001").fingerprint())
                .isEqualTo("new");
    }

    @Test
    void queriesAndAcknowledgementNeverCrossTenantConnectorOrObjectType() {
        assertThat(store.find(UUID.randomUUID(), connector, "SHIPMENT", "FH001")).isNull();
        assertThat(store.find(tenant, UUID.randomUUID(), "SHIPMENT", "FH001")).isNull();
        assertThat(store.find(tenant, connector, "SALES_ORDER", "FH001")).isNull();
        store.complete(
                UUID.randomUUID(),
                connector,
                "SHIPMENT",
                new Item("FH001", "old", "{}", now),
                true,
                now);
        assertThat(store.pendingCount(tenant, connector, "SHIPMENT")).isEqualTo(1);
    }

    @Test
    void missingDependencyWaitsForManualEntryUntilExplicitReplayAcknowledgesIt() {
        var item = store.find(tenant, connector, "SHIPMENT", "FH001");
        store.waitForManual(tenant, connector, "SHIPMENT", item, now);
        assertThat(store.awaitingManual(tenant, connector, "SHIPMENT", Set.of("FH001")))
                .containsExactly("FH001");
        assertThat(store.pending(tenant, connector, "SHIPMENT", 100, now.plusSeconds(86400)))
                .isEmpty();
        assertThat(store.pendingCount(tenant, connector, "SHIPMENT")).isEqualTo(1);
        store.complete(tenant, connector, "SHIPMENT", item, true, now.plusSeconds(86400));
        assertThat(store.awaitingManual(tenant, connector, "SHIPMENT", Set.of("FH001"))).isEmpty();
    }
}
