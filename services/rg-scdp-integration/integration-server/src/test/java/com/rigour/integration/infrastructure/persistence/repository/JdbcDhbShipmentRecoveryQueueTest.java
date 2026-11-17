package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.application.port.out.DhbIncrementalWorkStore.Item;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

class JdbcDhbShipmentRecoveryQueueTest {
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-17T06:00:00Z");
    SingleConnectionDataSource ds;
    JdbcTemplate db;
    JdbcDhbIncrementalWorkStore store;
    final Item item = new Item("FH1", "HASH", "{}", null);

    @BeforeEach
    void setup() {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        db = new JdbcTemplate(ds);
        store = new JdbcDhbIncrementalWorkStore(db);
        db.execute(
                "CREATE TABLE integration_dhb_incremental_work(tenant_id VARCHAR(36),connector_id"
                        + " VARCHAR(36),object_type VARCHAR(24),source_id VARCHAR(128),fingerprint"
                        + " VARCHAR(64),payload VARCHAR(1000),source_updated_at TIMESTAMP,applied"
                        + " INT,retry_policy VARCHAR(16),retry_at TIMESTAMP,checked_at TIMESTAMP)");
        db.update(
                "INSERT INTO integration_dhb_incremental_work"
                        + " VALUES(?,?,'SHIPMENT','FH1','HASH','{}',NULL,0,'MANUAL',?,NULL)",
                tenant.toString(),
                connector.toString(),
                java.time.LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void recoveredWorkIsResumedOnlyOnceAndThenCompleted() {
        assertThat(store.manualShipments(tenant, connector, 1000, now)).hasSize(1);
        assertThat(store.resumeShipment(tenant, connector, item, now)).isTrue();
        assertThat(store.resumeShipment(tenant, connector, item, now)).isFalse();
        assertThat(store.pending(tenant, connector, "SHIPMENT", 100, now)).hasSize(1);
        store.complete(tenant, connector, "SHIPMENT", item, true, now);
        assertThat(store.pendingCount(tenant, connector, "SHIPMENT")).isZero();
        assertThat(store.manualShipments(tenant, connector, 1000, now)).isEmpty();
    }

    @Test
    void newerSourceAndOtherTenantOrConnectorCannotBeResumedByOldReview() {
        assertThat(store.resumeShipment(UUID.randomUUID(), connector, item, now)).isFalse();
        assertThat(store.resumeShipment(tenant, UUID.randomUUID(), item, now)).isFalse();
        db.update("UPDATE integration_dhb_incremental_work SET fingerprint='NEW'");
        assertThat(store.resumeShipment(tenant, connector, item, now)).isFalse();
        store.deferShipmentReview(tenant, connector, item, now.plusSeconds(300));
        assertThat(store.manualShipments(tenant, connector, 1000, now)).hasSize(1);
    }

    @Test
    void unresolvedReviewBacksOffWithoutDroppingPendingWork() {
        store.deferShipmentReview(tenant, connector, item, now.plusSeconds(300));
        assertThat(store.manualShipments(tenant, connector, 1000, now.plusSeconds(299))).isEmpty();
        assertThat(store.manualShipments(tenant, connector, 1000, now.plusSeconds(300))).hasSize(1);
        assertThat(store.pendingCount(tenant, connector, "SHIPMENT")).isOne();
        assertThat(store.pending(tenant, connector, "SHIPMENT", 100, now.plusSeconds(1000)))
                .isEmpty();
    }
}
