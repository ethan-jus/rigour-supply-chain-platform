package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

class LogisticsImportPersistenceTest {
    JdbcTemplate jdbc;
    JdbcLogisticsImportStore store;

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
"""
CREATE TABLE order_logistics_import_order(id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36),enterprise_id VARCHAR(80),carrier_order_no VARCHAR(80),file_name VARCHAR(200),file_hash VARCHAR(64),source_json CLOB,state VARCHAR(20),cost_id VARCHAR(36),revision INT,created_by VARCHAR(50),created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,carrier_order_no))
""");
        for (var column :
                List.of(
                        "source_channel VARCHAR(16) DEFAULT 'EXCEL'",
                        "latest_source_json CLOB",
                        "source_hash VARCHAR(64)",
                        "latest_source_hash VARCHAR(64)",
                        "source_difference INT DEFAULT 0",
                        "last_synced_time TIMESTAMP",
                        "bill_verification_json CLOB"))
            jdbc.execute("ALTER TABLE order_logistics_import_order ADD " + column);
        jdbc.execute(
                "CREATE TABLE order_logistics_cost(tenant_id VARCHAR(36),carrier_code"
                        + " VARCHAR(40),tracking_no VARCHAR(100))");
        store = new JdbcLogisticsImportStore(jdbc, JsonMapper.builder().build());
    }

    Upload upload(String enterprise, String amount) {
        return new Upload(
                "货拉拉.xlsx",
                "a".repeat(64),
                List.of(
                        new CarrierOrder(
                                enterprise,
                                "3616894445226123730",
                                "已完成",
                                "2026-11-06",
                                new BigDecimal(amount),
                                Map.of("收货人信息", "星河门店"),
                                List.of(),
                                4)));
    }

    @Test
    void repeatUploadRetainsOriginalReceiptAndNeverDuplicatesAcrossAccountsWithinTenant() {
        var first = store.stage("T1", "U1", upload("AC1", "40.50")).getFirst();
        var repeated = store.stage("T1", "U1", upload("AC1", "99.00")).getFirst();
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.order().paidAmount()).isEqualByComparingTo("40.50");
        assertThat(repeated.order().orderNo()).isEqualTo("3616894445226123730");
        assertThat(store.stage("T1", "U2", upload("AC2", "99.00"))).isEmpty();
        assertThat(store.rows("T1", "U1", 0)).hasSize(1);
        assertThat(store.stage("T2", "U2", upload("AC1", "20"))).hasSize(1);
        assertThat(store.rows("T1", "U2", 0)).isEmpty();
    }

    @Test
    void actorTenantAndRevisionProtectLinkingAndGeneratedReceiptSurvivesReupload() {
        var first = store.stage("T1", "U1", upload("AC1", "40.50")).getFirst();
        assertThatThrownBy(() -> store.lock("T1", "U2", first.id())).hasMessageContaining("无权限");
        assertThatThrownBy(() -> store.lock("T2", "U1", first.id())).hasMessageContaining("无权限");
        store.generated("T1", first.id(), 1, "C1");
        assertThatThrownBy(() -> store.generated("T1", first.id(), 1, "C2"))
                .hasMessageContaining("已变化");
        var repeated = store.stage("T1", "U1", upload("AC1", "40.50")).getFirst();
        assertThat(repeated.costId()).isEqualTo("C1");
        assertThat(repeated.state()).isEqualTo("GENERATED");
        assertThat(repeated.revision()).isEqualTo(2);
    }

    ApiSource api(String amount, String hash) {
        var old = upload("AC1", amount).rows().getFirst();
        var f =
                new SourceFacts(
                        "connector",
                        "raw",
                        "user",
                        null,
                        "员工甲",
                        null,
                        new BigDecimal(amount),
                        new BigDecimal(amount),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "2026-11-06",
                        "2026-11-06",
                        "FINAL",
                        null,
                        List.of(),
                        List.of());
        return new ApiSource(
                "connector",
                hash.repeat(64),
                new CarrierOrder(
                        old.enterpriseId(),
                        old.orderNo(),
                        old.status(),
                        old.useTime(),
                        old.paidAmount(),
                        old.raw(),
                        List.of(),
                        0,
                        f));
    }

    @Test
    void apiAndExcelShareOneOrderAndFrozenCostEvidenceSurvivesNewBill() {
        var excel = store.stage("T1", "U1", upload("AC1", "40.50")).getFirst();
        var received = store.receive("T1", "SYSTEM", api("40.50", "a"));
        assertThat(received.id()).isEqualTo(excel.id());
        assertThat(received.sourceChannel()).isEqualTo("API");
        store.generated("T1", received.id(), received.revision(), "C1");
        var changed = store.receive("T1", "SYSTEM", api("45.00", "b"));
        assertThat(changed.order().facts().billAmount()).isEqualByComparingTo("40.50");
        assertThat(changed.latestOrder().facts().billAmount()).isEqualByComparingTo("45.00");
        assertThat(changed.sourceDifference()).isTrue();
        assertThat(changed.syncOutcome()).isEqualTo("UPDATED");
        assertThat(store.receive("T1", "SYSTEM", api("45.00", "b")).revision())
                .isEqualTo(changed.revision());
        assertThat(store.receive("T1", "SYSTEM", api("45.00", "b")).syncOutcome())
                .isEqualTo("UNCHANGED");
    }

    @Test
    void administratorCanReadServiceQueueWithoutGivingOrdinaryUploadersTenantWideAccess() {
        var row = store.receive("T1", "SYSTEM", api("40.50", "a"));
        assertThat(store.query("T1", "U2", false, new SourceQuery(0, null, null, false))).isEmpty();
        assertThat(store.query("T1", "U2", true, new SourceQuery(0, null, null, false))).hasSize(1);
        assertThat(store.query("T2", "U2", true, new SourceQuery(0, null, null, false))).isEmpty();
        assertThat(store.lockManaged("T1", "U2", true, row.id()).id()).isEqualTo(row.id());
    }

    @Test
    void repeatedApiReceiptDoesNotClearVerificationButChangedBillInvalidatesIt() {
        var row = store.receive("T1", "SYSTEM", api("40.50", "a"));
        store.verifyBill(
                "T1",
                row.id(),
                row.revision(),
                new BillVerification(
                        new BigDecimal("40.50"), "核对账单", "U1", java.time.Instant.now()));
        assertThat(store.receive("T1", "SYSTEM", api("40.50", "a")).verification()).isNotNull();
        assertThat(store.receive("T1", "SYSTEM", api("45.00", "b")).verification()).isNull();
    }
}
