package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

public class LogisticsImportPersistenceTest {
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
                        "bill_verification_json CLOB",
                        "bill_history_json CLOB"))
            jdbc.execute("ALTER TABLE order_logistics_import_order ADD " + column);
        jdbc.execute(
                "CREATE TABLE order_logistics_cost(tenant_id VARCHAR(36),carrier_code"
                        + " VARCHAR(40),tracking_no VARCHAR(100))");
        jdbc.execute(
                "CREATE TABLE order_logistics_cost_audit(tenant_id VARCHAR(36),entry_id"
                        + " VARCHAR(36),action_code VARCHAR(40),actor_id VARCHAR(36),reason"
                        + " VARCHAR(500),snapshot_json CLOB)");
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

    public static String jsonExtract(String body, String path) {
        if (body == null) return null;
        var node = new tools.jackson.databind.ObjectMapper().readTree(body);
        for (String key : path.substring(2).split("\\.")) node = node.path(key);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    public static String jsonUnquote(String text) {
        return text;
    }

    @Test
    void platformNumberGuardDoesNotCompareWithWaybillOrAnotherTenantsNumber() {
        jdbc.execute("ALTER TABLE order_logistics_cost ADD id VARCHAR(36)");
        jdbc.execute("ALTER TABLE order_logistics_cost ADD data_json CLOB");
        jdbc.execute(
                "CREATE ALIAS JSON_EXTRACT FOR '"
                        + LogisticsImportPersistenceTest.class.getName()
                        + ".jsonExtract'");
        jdbc.execute(
                "CREATE ALIAS JSON_UNQUOTE FOR '"
                        + LogisticsImportPersistenceTest.class.getName()
                        + ".jsonUnquote'");
        jdbc.update(
                "INSERT INTO order_logistics_cost(tenant_id,id,carrier_code,tracking_no,data_json)"
                    + " VALUES('T1','C1','HLL','WB-001',?)",
                "{\"ledger\":{\"platformOrderNo\":\"3616894445226123730\"}}");
        assertThat(store.trackingExists("T1", "3616894445226123730")).isTrue();
        assertThat(store.trackingExists("T1", "WB-001")).isFalse();
        assertThat(store.trackingExists("T2", "3616894445226123730")).isFalse();
        var guarded = new JdbcPlatformTransportStore(jdbc, JsonMapper.builder().build(), store);
        var command =
                JsonMapper.builder()
                        .build()
                        .readValue(
                                "{\"ledger\":{\"schemaVersion\":2,\"carrierCode\":\"HLL\",\"trackingNo\":\"WB-NEW\",\"platformOrderNo\":\"3616894445226123730\"}}",
                                com.rigour.order.api.v1.model.LogisticsCostCommand.class);
        assertThatThrownBy(() -> guarded.assertReadOnly("T1", null, command))
                .hasMessageContaining("已有运输记录");
        assertThatCode(() -> guarded.assertReadOnly("T2", null, command))
                .doesNotThrowAnyException();
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

    @Test
    void partialListNeverErasesDetailAndResyncKeepsOwnCosVoucher() {
        var tree =
                (tools.jackson.databind.node.ObjectNode)
                        JsonMapper.builder().build().valueToTree(api("137.64", "a").order());
        tree.withObject("raw").put("详情状态", "DETAIL");
        var fees = tree.withObject("facts").putArray("fees");
        fees.addObject()
                .put("code", "23")
                .put("name", "路桥费")
                .put("amount", 20)
                .put("lineId", "fee-0")
                .putArray("vouchers")
                .addObject()
                .put("id", "V1")
                .put("name", "凭证")
                .put("state", "READY")
                .put("fileKey", "T1/integration-huolala-vouchers/C/O/V1.png");
        var json = JsonMapper.builder().build();
        var full = json.treeToValue(tree, CarrierOrder.class);
        var first = store.receive("T1", "SYSTEM", new ApiSource("connector", "a".repeat(64), full));
        store.generated("T1", first.id(), first.revision(), "C1");
        var summary =
                (tools.jackson.databind.node.ObjectNode) json.valueToTree(api("0", "b").order());
        summary.withObject("raw").put("详情状态", "LIST");
        store.receive(
                "T1",
                "SYSTEM",
                new ApiSource(
                        "connector",
                        "b".repeat(64),
                        json.treeToValue(summary, CarrierOrder.class)));
        var kept = store.byCost("T1", "C1").orElseThrow();
        assertThat(kept.latestOrder().facts().billAmount()).isEqualByComparingTo("137.64");
        assertThat(kept.sourceDifference()).isFalse();
        ((tools.jackson.databind.node.ObjectNode) tree.at("/facts/fees/0/vouchers/0"))
                .put("state", "PENDING")
                .putNull("fileKey");
        var updated =
                store.receive(
                        "T1",
                        "SYSTEM",
                        new ApiSource(
                                "connector",
                                "a".repeat(64),
                                json.treeToValue(tree, CarrierOrder.class)));
        assertThat(updated.latestOrder().facts().fees().getFirst().vouchers().getFirst().fileKey())
                .contains("integration-huolala-vouchers");
        assertThat(updated.syncOutcome()).isEqualTo("UNCHANGED");
    }

    @Test
    void omittedFeeDetailsAndMoneyScaleDoNotInventBillChangesOrClearVerification() {
        var json = JsonMapper.builder().build();
        var tree =
                (tools.jackson.databind.node.ObjectNode)
                        json.valueToTree(api("137.64", "a").order());
        tree.withObject("raw").put("详情状态", "DETAIL").put("费用明细已提供", "true");
        tree.withObject("facts")
                .putArray("fees")
                .addObject()
                .put("code", "23")
                .put("name", "路桥费")
                .put("amount", new BigDecimal("20.00"));
        var first =
                store.receive(
                        "T1",
                        "SYSTEM",
                        new ApiSource(
                                "connector",
                                "a".repeat(64),
                                json.treeToValue(tree, CarrierOrder.class)));
        store.generated("T1", first.id(), first.revision(), "C1");
        var linked = store.byCost("T1", "C1").orElseThrow();
        store.verifyBill(
                "T1",
                first.id(),
                linked.revision(),
                new BillVerification(
                        new BigDecimal("137.64"), "已核对", "U1", java.time.Instant.now()));
        var revision = store.byCost("T1", "C1").orElseThrow().revision();
        tree.withObject("raw").put("费用明细已提供", "false");
        tree.withObject("facts").putArray("fees");
        tree.withObject("facts")
                .putNull("billAmount")
                .putNull("refundAmount")
                .putNull("couponAmount")
                .putNull("bonusAmount");
        tree.putNull("paidAmount");
        var missing =
                store.receive(
                        "T1",
                        "SYSTEM",
                        new ApiSource(
                                "connector",
                                "b".repeat(64),
                                json.treeToValue(tree, CarrierOrder.class)));
        assertThat(missing.syncOutcome()).isEqualTo("UNCHANGED");
        assertThat(missing.revision()).isEqualTo(revision);
        assertThat(missing.sourceDifference()).isFalse();
        assertThat(missing.verification()).isNotNull();
        assertThat(missing.latestOrder().facts().fees()).hasSize(1);
        assertThat(missing.latestOrder().facts().billAmount()).isEqualByComparingTo("137.64");
        assertThat(missing.latestOrder().paidAmount()).isEqualByComparingTo("137.64");
        tree.withObject("raw").put("费用明细已提供", "true");
        tree.withObject("facts")
                .putArray("fees")
                .addObject()
                .put("code", "23")
                .put("name", "路桥费")
                .put("amount", 20);
        assertThat(
                        store.receive(
                                        "T1",
                                        "SYSTEM",
                                        new ApiSource(
                                                "connector",
                                                "c".repeat(64),
                                                json.treeToValue(tree, CarrierOrder.class)))
                                .syncOutcome())
                .isEqualTo("UNCHANGED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT bill_history_json FROM order_logistics_import_order WHERE"
                                        + " id=?",
                                String.class,
                                first.id()))
                .isNull();
        tree.withObject("facts").put("billAmount", new BigDecimal("142.64"));
        var changed =
                store.receive(
                        "T1",
                        "SYSTEM",
                        new ApiSource(
                                "connector",
                                "d".repeat(64),
                                json.treeToValue(tree, CarrierOrder.class)));
        assertThat(changed.sourceDifference()).isTrue();
        assertThat(changed.verification()).isNull();
        assertThat(changed.order().facts().billAmount()).isEqualByComparingTo("137.64");
        assertThat(changed.latestOrder().facts().billAmount()).isEqualByComparingTo("142.64");
    }

    @Test
    void employeeBindingUpdatesExistingSourcesAndStaleSyncCannotRevertOrChangeCostEvidence() {
        var first = store.receive("T1", "SYSTEM", api("40.50", "a"));
        store.generated("T1", first.id(), first.revision(), "C1");
        store.receive("T2", "SYSTEM", api("40.50", "a"));
        var json = JsonMapper.builder().build();
        var second =
                (tools.jackson.databind.node.ObjectNode)
                        json.valueToTree(api("40.50", "a").order());
        second.put("orderNo", "3616894445226123731");
        store.receive(
                "T1",
                "SYSTEM",
                new ApiSource(
                        "connector", "a".repeat(64), json.treeToValue(second, CarrierOrder.class)));
        store.employeeMapping(
                "T1", new EmployeeMappingUpdate("other-connector", "AC1", "user", "OTHER", 3));
        assertThat(
                        store.byCost("T1", "C1")
                                .orElseThrow()
                                .latestOrder()
                                .facts()
                                .mappedEmployeeCode())
                .isNull();
        store.employeeMapping(
                "T1", new EmployeeMappingUpdate("connector", "AC1", "user", "EMP1", 1));
        var bound = store.byCost("T1", "C1").orElseThrow();
        assertThat(bound.order().facts().mappedEmployeeCode()).isEqualTo("EMP1");
        assertThat(bound.latestOrder().facts().mappedEmployeeCode()).isEqualTo("EMP1");
        assertThat(store.rows("T1", "SYSTEM", 0))
                .hasSize(2)
                .allSatisfy(
                        r -> assertThat(r.order().facts().mappedEmployeeCode()).isEqualTo("EMP1"));
        assertThat(bound.sourceDifference()).isFalse();
        assertThat(store.rows("T2", "SYSTEM", 0).getFirst().order().facts().mappedEmployeeCode())
                .isNull();
        store.employeeMapping("T1", new EmployeeMappingUpdate("connector", "AC1", "user", null, 3));
        store.employeeMapping(
                "T1", new EmployeeMappingUpdate("connector", "AC1", "user", "EMP2", 2));
        var stale = store.receive("T1", "SYSTEM", api("40.50", "a"));
        assertThat(stale.latestOrder().facts().mappedEmployeeCode()).isNull();
        assertThat(stale.latestOrder().facts().employeeMappingRevision()).isEqualTo(3);
        assertThat(stale.syncOutcome()).isEqualTo("UNCHANGED");
        assertThat(stale.order().facts().billAmount()).isEqualByComparingTo("40.50");
        assertThat(stale.costId()).isEqualTo("C1");
        assertThat(stale.revision()).isEqualTo(bound.revision());
    }

    @Test
    void unchangedFeeKeepsPendingVoucherUntilIndependentDownloadCompletes() {
        var json = JsonMapper.builder().build();
        var tree =
                (tools.jackson.databind.node.ObjectNode)
                        json.valueToTree(api("40.50", "a").order());
        tree.withObject("raw").put("详情状态", "DETAIL").put("费用明细已提供", "true");
        var fee =
                tree.withObject("facts")
                        .putArray("fees")
                        .addObject()
                        .put("code", "23")
                        .put("name", "路桥费")
                        .put("amount", new BigDecimal("20.00"))
                        .put("lineId", "fee-0");
        fee.putArray("vouchers")
                .addObject()
                .put("id", "V1")
                .put("name", "路桥凭证")
                .put("state", "PENDING");
        var first =
                store.receive(
                        "T1",
                        "SYSTEM",
                        new ApiSource(
                                "connector",
                                "a".repeat(64),
                                json.treeToValue(tree, CarrierOrder.class)));
        store.generated("T1", first.id(), first.revision(), "C1");
        fee.put("amount", 20).putArray("vouchers");
        var repeated =
                store.receive(
                        "T1",
                        "SYSTEM",
                        new ApiSource(
                                "connector",
                                "b".repeat(64),
                                json.treeToValue(tree, CarrierOrder.class)));
        assertThat(repeated.syncOutcome()).isEqualTo("UNCHANGED");
        assertThat(repeated.latestOrder().facts().fees().getFirst().vouchers()).hasSize(1);
        assertThat(repeated.latestOrder().facts().fees().getFirst().vouchers().getFirst().state())
                .isEqualTo("PENDING");
    }
}
