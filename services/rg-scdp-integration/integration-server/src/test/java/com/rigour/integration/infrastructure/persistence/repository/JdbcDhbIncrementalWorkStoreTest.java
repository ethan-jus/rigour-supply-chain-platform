package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.application.port.out.DhbIncrementalWorkStore.Item;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;

@Testcontainers
class JdbcDhbIncrementalWorkStoreTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static JdbcDhbIncrementalWorkStore store;
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID();
    final Instant now = Instant.parse("2026-09-22T10:00:00Z");

    @BeforeAll
    static void setup() {
        var ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V26__dhb_incremental_work.sql"),
                        new ClassPathResource("db/migration/V32__dhb_shipment_manual_dependency_recovery.sql"))
                .execute(ds);
        store = new JdbcDhbIncrementalWorkStore(new JdbcTemplate(ds));
    }

    Item item(String id, String hash) {
        return new Item(id, hash.repeat(64), "{\"id\":\"" + id + "\"}", now);
    }

    @Test
    void onlyAppliedSameVersionsSkipAndFailedItemsSurviveRestart() {
        var a = item("a", "a");
        var b = item("b", "b");
        assertThat(store.stage(tenant, connector, "SALES_ORDER", List.of(a, b), now)).isEmpty();
        store.complete(tenant, connector, "SALES_ORDER", a, true, now);
        store.complete(tenant, connector, "SALES_ORDER", b, false, now);
        assertThat(store.stage(tenant, connector, "SALES_ORDER", List.of(a, b), now))
                .containsExactly("a");
        assertThat(store.pending(tenant, connector, "SALES_ORDER", 100, now.plusSeconds(301)))
                .extracting(Item::id)
                .containsExactly("b");
        assertThat(store.pendingCount(tenant, connector, "SALES_ORDER")).isEqualTo(1);
        assertThat(store.pendingCount(UUID.randomUUID(), connector, "SALES_ORDER")).isZero();
        // A repaired association can acknowledge the unchanged pending version on the next cycle.
        store.complete(tenant, connector, "SALES_ORDER", b, true, now.plusSeconds(3600));
        assertThat(store.pendingCount(tenant, connector, "SALES_ORDER")).isZero();
        assertThat(store.stage(tenant, connector, "SALES_ORDER", List.of(a, b), now.plusSeconds(3600)))
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void newFingerprintCannotBeAcknowledgedByOldWorkerAndExpiresFastSkip() {
        var a = item("a", "a");
        var newer = item("a", "b");
        store.stage(tenant, connector, "SALES_ORDER", List.of(a), now);
        store.complete(tenant, connector, "SALES_ORDER", a, true, now);
        assertThat(
                        store.stage(
                                tenant,
                                connector,
                                "SALES_ORDER",
                                List.of(newer),
                                now.plusSeconds(1)))
                .isEmpty();
        store.complete(tenant, connector, "SALES_ORDER", a, true, now.plusSeconds(2));
        assertThat(store.pendingCount(tenant, connector, "SALES_ORDER")).isEqualTo(1);
        store.complete(tenant, connector, "SALES_ORDER", newer, true, now.plusSeconds(3));
        assertThat(
                        store.stage(
                                tenant,
                                connector,
                                "SALES_ORDER",
                                List.of(newer),
                                now.plusSeconds(86404)))
                .isEmpty();
    }

    @Test
    void manualDependencyFailureNeedsExplicitReplayUnlessSourceChanges() {
        var first = item("FH001", "a");
        store.stage(tenant, connector, "SHIPMENT", List.of(first), now);
        store.waitForManual(tenant, connector, "SHIPMENT", first, now);
        store.stage(tenant, connector, "SHIPMENT", List.of(first), now.plusSeconds(3600));
        assertThat(store.awaitingManual(tenant, connector, "SHIPMENT", Set.of("FH001")))
                .containsExactly("FH001");
        assertThat(store.pending(tenant, connector, "SHIPMENT", 100, now.plusSeconds(3600)))
                .isEmpty();
        assertThat(store.pendingCount(tenant, connector, "SHIPMENT")).isEqualTo(1);

        var newer = new Item("FH001", "b".repeat(64), "{}", now.plusSeconds(2));
        store.stage(tenant, connector, "SHIPMENT", List.of(newer), now.plusSeconds(3601));
        assertThat(store.awaitingManual(tenant, connector, "SHIPMENT", Set.of("FH001"))).isEmpty();
        assertThat(store.pending(tenant, connector, "SHIPMENT", 100, now.plusSeconds(3602)))
                .extracting(Item::fingerprint).containsExactly(newer.fingerprint());
        store.waitForManual(tenant, connector, "SHIPMENT", first, now.plusSeconds(3603));
        assertThat(store.awaitingManual(tenant, connector, "SHIPMENT", Set.of("FH001"))).isEmpty();
        store.waitForManual(tenant, connector, "SHIPMENT", newer, now.plusSeconds(3604));
        store.complete(tenant, connector, "SHIPMENT", newer, true, now.plusSeconds(3605));
        assertThat(store.pendingCount(tenant, connector, "SHIPMENT")).isZero();
    }

    @Test
    void missingOrderVersionNeverSkipsDetailValidation() {
        var a = new Item("a", "a".repeat(64), "{}", null);
        store.stage(tenant, connector, "SALES_ORDER", List.of(a), now);
        store.complete(tenant, connector, "SALES_ORDER", a, true, now);
        assertThat(store.stage(tenant, connector, "SALES_ORDER", List.of(a), now)).isEmpty();
    }

    @Test
    void lateOlderPageCannotReplaceNewerFailedWork() {
        var older = item("a", "a");
        var newer = new Item("a", "b".repeat(64), "{}", now.plusSeconds(5));
        store.stage(tenant, connector, "RECEIPT", List.of(newer), now.plusSeconds(6));
        assertThat(store.stage(tenant, connector, "RECEIPT", List.of(older), now.plusSeconds(7)))
                .containsExactly("a");
        assertThat(store.pending(tenant, connector, "RECEIPT", 100, now.plusSeconds(8)))
                .extracting(Item::fingerprint)
                .containsExactly(newer.fingerprint());
        store.complete(tenant, connector, "RECEIPT", older, true, now.plusSeconds(9));
        assertThat(store.pendingCount(tenant, connector, "RECEIPT")).isEqualTo(1);
    }
}
