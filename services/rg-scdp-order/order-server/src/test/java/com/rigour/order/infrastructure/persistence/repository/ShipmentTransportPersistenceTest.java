package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.ShipmentTransportModels.*;
import com.rigour.order.application.port.out.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.*;
import java.util.*;

class ShipmentTransportPersistenceTest {
    final JsonMapper json = JsonMapper.builder().build();
    final PlatformTransportStore platform = mock(PlatformTransportStore.class);
    final OrderLogisticsCostStore costs = mock(OrderLogisticsCostStore.class);
    SingleConnectionDataSource ds;
    JdbcTemplate db;
    JdbcShipmentTransportStore store;
    final UUID connector = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        db = new JdbcTemplate(ds);
        for (String migration :
                List.of(
                        "V54__order_logistics_cost.sql",
                        "V74__sales_shipment_transport_sources.sql")) {
            String ddl =
                    Files.readString(Path.of("src/main/resources/db/migration", migration))
                            .replace(
                                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                                            + " COLLATE=utf8mb4_general_ci",
                                    "")
                            .replace(" JSON ", " LONGTEXT ");
            for (String sql : ddl.split(";")) if (!sql.isBlank()) db.execute(sql);
        }
        for (String column :
                List.of(
                        "deleted INT DEFAULT 0",
                        "source_system VARCHAR(32)",
                        "carrier_code VARCHAR(64)",
                        "delivery_mode_code VARCHAR(64)",
                        "cost_month VARCHAR(7)",
                        "origin_biz_key VARCHAR(255)",
                        "transport_purpose_code VARCHAR(64)"))
            db.execute("ALTER TABLE order_logistics_cost ADD " + column);
        db.execute("ALTER TABLE order_logistics_cost DROP CONSTRAINT ck_cost_status");
        db.execute(
                "ALTER TABLE order_logistics_cost ADD CONSTRAINT ck_cost_status CHECK(status_code"
                        + " IN ('ACTIVE','VOID'))");
        db.execute(
                "ALTER TABLE order_transport_shipment_source ALTER COLUMN sales_order_id DROP NOT"
                        + " NULL");
        store = new JdbcShipmentTransportStore(db, json, platform, costs);
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    Command command(String status, int version) {
        return new Command(
                connector,
                "D1",
                "FH001",
                11L,
                null,
                status,
                Instant.parse("2026-11-13T01:00:00Z"),
                Instant.parse("2026-11-13T01:00:00Z").plusSeconds(version),
                null,
                "来源承运商",
                null,
                "第三方配送",
                List.of("SF001"),
                null,
                Map.of("note", "v" + version),
                List.of());
    }

    LogisticsCostCommand transport() {
        return json.readValue(
"""
{"requestId":"test","title":"销售订单配送","typeCode":"DELIVERY","cityCode":"WH","costDate":"2026-11-13","paymentStatusCode":"UNKNOWN",
 "ledger":{"schemaVersion":2,"transportPurposeCode":"DELIVERY","costMonth":"2026-11",
   "sender":{"pointCode":"FROM","partyTypeCode":"OTHER","manualName":"武汉仓"},
   "receivingPoints":[{"pointCode":"TO0","partyTypeCode":"CUSTOMER","partyCode":"CUS1","displayName":"星河门店"}],
   "feeItems":[{"feeKey":"freight","feeTypeCode":"FREIGHT","bearerCode":"COMPANY","allocationMethodCode":"EQUAL"}],
   "sources":[{"orderTypeCode":"SALES_ORDER","orderCode":"SO001","linkRoleCode":"COST","allocations":[]}],"scopeCityCodes":["WH"]}}
""",
                LogisticsCostCommand.class);
    }

    Source sync(String status, int version) {
        return store.sync(
                "T",
                "U",
                command(status, version),
                transport(),
                json.readValue("{\"documentNo\":\"SO001\"}", LogisticsCostSourceView.class),
                null);
    }

    @Test
    void repeatAndChangedSourceNeverCreateAnotherCostAndFreightRemainsUnknown() {
        var one = sync("receivedin", 1);
        var two = sync("receivedin", 1);
        var three = sync("received", 2);
        assertThat(one.costId()).isEqualTo(two.costId()).isEqualTo(three.costId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT amount FROM order_logistics_cost",
                                java.math.BigDecimal.class))
                .isNull();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM order_transport_shipment_history",
                                Integer.class))
                .isEqualTo(2);
        assertThat(store.byCosts("OTHER", List.of(one.costId()))).isEmpty();
        assertThat(store.byCosts("T", List.of(one.costId())).get(one.costId())).hasSize(1);
    }

    @Test
    void exactDhbShipmentReusesHuolalaCostAndPersistsSalesSharesWithoutTakingTheNewBillAmount() {
        var prior = sync("receivedin", 1);
        db.execute(
                "CREATE TABLE order_logistics_import_order(tenant_id VARCHAR(64),cost_id"
                        + " VARCHAR(36),carrier_order_no VARCHAR(80))");
        db.execute(
                "CREATE ALIAS JSON_EXTRACT FOR '"
                        + LogisticsImportPersistenceTest.class.getName()
                        + ".jsonExtract'");
        db.execute(
                "CREATE ALIAS JSON_UNQUOTE FOR '"
                        + LogisticsImportPersistenceTest.class.getName()
                        + ".jsonUnquote'");
        db.update(
                "UPDATE order_logistics_cost SET source_system='HUOLALA',carrier_code='HLL' WHERE"
                        + " id=?",
                prior.costId());
        db.update("INSERT INTO order_logistics_import_order VALUES('T',?,'SF001')", prior.costId());
        var old =
                json.readValue(
                        """
                        {"id":"%s","revision":1,"statusCode":"ACTIVE","data":{"requestId":"%s","costDate":"2026-11-13","amount":99,"paymentStatusCode":"PAID"},"sources":[]}
                        """
                                .formatted(prior.costId(), UUID.randomUUID()),
                        LogisticsCostDetailView.class);
        when(costs.entry(any(), any(), eq(prior.costId()), any())).thenReturn(old);
        var order =
                json.readValue(
                        """
                        {"orderNo":"SF001","line":0,"useTime":"2026-11-13 09:00:00","raw":{},"facts":{"billAmount":142.64}}
                        """,
                        com.rigour.order.api.v1.model.LogisticsImportModels.CarrierOrder.class);
        var row =
                new com.rigour.order.api.v1.model.LogisticsImportModels.Row(
                        "P",
                        1,
                        "GENERATED",
                        prior.costId(),
                        "API",
                        order,
                        "API",
                        false,
                        order,
                        null,
                        null,
                        null);
        when(platform.detail("T", prior.costId()))
                .thenReturn(new TransportRecordModels.PlatformRecord(row, null, List.of()));
        var command =
                (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 2));
        command.put("shipmentId", "D2");
        command.put("shipmentNo", "FH002");
        var data = (tools.jackson.databind.node.ObjectNode) json.valueToTree(transport());
        ((tools.jackson.databind.node.ObjectNode) data.path("ledger")).put("carrierCode", "HLL");
        ((tools.jackson.databind.node.ObjectNode) data.path("ledger").path("sources").get(0))
                .set(
                        "allocations",
                        json.readTree(
                                "[{\"feeKey\":\"freight\",\"receivingPointCode\":\"TO0\",\"cityCode\":\"WH\",\"basis\":1,\"amount\":0}]"));
        var snapshot =
                json.readValue(
                        "{\"documentNo\":\"SO001\",\"sourceType\":\"SALES_ORDER\",\"cityCode\":\"WH\",\"employeeCode\":\"EMP1\"}",
                        LogisticsCostSourceView.class);
        var result =
                store.sync(
                        "T",
                        "U",
                        json.treeToValue(command, Command.class),
                        json.treeToValue(data, LogisticsCostCommand.class),
                        snapshot,
                        null);
        assertThat(result.costId()).isEqualTo(prior.costId());
        var saved = org.mockito.ArgumentCaptor.forClass(LogisticsCostCommand.class);
        verify(costs)
                .save(
                        eq("T"),
                        eq("LOGISTICS"),
                        eq(prior.costId()),
                        saved.capture(),
                        any(),
                        any(),
                        any(),
                        eq("U"),
                        any());
        assertThat(saved.getValue().amount()).isEqualByComparingTo("99");
        assertThat(saved.getValue().ledger().sources().getFirst().allocations().getFirst().amount())
                .isEqualByComparingTo("99");
        verify(platform).saveAssociations(eq("T"), eq(prior.costId()), any(), any(), eq("U"));
        verify(platform, never()).saveDraft(any(), any(), any(), any(), any());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void idAndNumberFallbackShareOneTransportInBothDirectionsAndConflictingIdsAreRejected() {
        var one = sync("receivedin", 1);
        var fallback =
                (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 2));
        fallback.put("shipmentId", "FH001");
        var order = json.readValue("{\"documentNo\":\"SO001\"}", LogisticsCostSourceView.class);
        var replay =
                store.sync(
                        "T",
                        "U",
                        json.treeToValue(fallback, Command.class),
                        transport(),
                        order,
                        null);
        assertThat(replay.costId()).isEqualTo(one.costId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT source_shipment_id FROM order_transport_shipment_source",
                                String.class))
                .isEqualTo("D1");
        fallback.put("shipmentId", "D2");
        assertThatThrownBy(
                        () ->
                                store.sync(
                                        "T",
                                        "U",
                                        json.treeToValue(fallback, Command.class),
                                        transport(),
                                        order,
                                        null))
                .hasMessageContaining("来源编号不一致");
        db.update("UPDATE order_transport_shipment_source SET source_shipment_id='FH001'");
        assertThat(sync("receivedin", 3).costId()).isEqualTo(one.costId());
        assertThat(
                        db.queryForObject(
                                "SELECT source_shipment_id FROM order_transport_shipment_source",
                                String.class))
                .isEqualTo("D1");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void waitingIsNotShippedAndCancellationCannotOverwriteConfirmedMoneyOrBusinessSnapshot() {
        assertThat(sync("shipped", 1).costId()).isNull();
        var source = sync("receivedin", 2);
        db.update("UPDATE order_logistics_cost SET status_code='ACTIVE',amount=137.64");
        String original =
                db.queryForObject("SELECT data_json FROM order_logistics_cost", String.class);
        assertThat(sync("cancelled", 3).differencePending()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT amount FROM order_logistics_cost",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("137.64");
        assertThat(db.queryForObject("SELECT data_json FROM order_logistics_cost", String.class))
                .isEqualTo(original);
        assertThat(sync("receivedin", 1).latest().sourceStatus()).isEqualTo("cancelled");
    }

    @Test
    void trackingCanCreatePendingCostBeforeSalesMappingAndLaterReuseIt() {
        var unlinked =
                (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 1));
        unlinked.putNull("salesOrderId");
        var empty = transport().withLedger(transport().ledger().withSources(List.of()), null);
        var first =
                store.sync("T", "U", json.treeToValue(unlinked, Command.class), empty, null, null);
        assertThat(first.costId()).isNotNull();
        assertThat(
                        json.readTree(
                                        db.queryForObject(
                                                "SELECT sources_json FROM order_logistics_cost"
                                                        + " WHERE id=?",
                                                String.class,
                                                first.costId()))
                                .isEmpty())
                .isTrue();
        var source =
                json.readValue(
                        "{\"documentNo\":\"SO001\",\"documentAmount\":100,\"employeeCode\":\"EMP1\",\"departmentId\":7}",
                        LogisticsCostSourceView.class);
        var next = store.sync("T", "U", command("receivedin", 2), transport(), source, null);
        assertThat(next.costId()).isEqualTo(first.costId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThat(
                        json.readTree(
                                        db.queryForObject(
                                                "SELECT sources_json FROM order_logistics_cost",
                                                String.class))
                                .size())
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT sales_order_id FROM order_transport_shipment_source",
                                Long.class))
                .isEqualTo(11L);
    }
}
