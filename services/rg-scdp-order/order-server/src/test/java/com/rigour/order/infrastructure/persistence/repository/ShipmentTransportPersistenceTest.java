package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.api.v1.model.ShipmentTransportModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.order.application.service.cost.TransportAllocationPlan;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class ShipmentTransportPersistenceTest {
    final JsonMapper json = JsonMapper.builder().build();
    final PlatformTransportStore platform = mock(PlatformTransportStore.class);
    final OrderLogisticsCostStore costs = mock(OrderLogisticsCostStore.class);
    Connection keeper;
    TransactionTemplate transactions;
    JdbcTemplate db;
    JdbcShipmentTransportStore store;
    final UUID connector = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        keeper = ds.getConnection();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        db = new JdbcTemplate(new SingleConnectionDataSource(keeper, true));
        for (String migration :
                List.of(
                        "V54__order_logistics_cost.sql",
                        "V56__cost_attachments.sql",
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
                        "tracking_no VARCHAR(160)",
                        "amount_manually_edited INT DEFAULT 0",
                        "delivery_mode_code VARCHAR(64)",
                        "cost_month VARCHAR(7)",
                        "origin_biz_key VARCHAR(255)",
                        "transport_purpose_code VARCHAR(64)"))
            db.execute("ALTER TABLE order_logistics_cost ADD " + column);
        db.execute("ALTER TABLE order_transport_shipment_source ADD awaiting_huolala_no VARCHAR(80)");
        db.execute("ALTER TABLE order_transport_shipment_source ADD awaiting_huolala_data LONGTEXT");
        db.execute("ALTER TABLE order_logistics_cost DROP CONSTRAINT ck_cost_status");
        db.execute(
                "ALTER TABLE order_logistics_cost ADD CONSTRAINT ck_cost_status CHECK(status_code"
                        + " IN ('ACTIVE','VOID'))");
        db.execute(
                "ALTER TABLE order_transport_shipment_source ALTER COLUMN sales_order_id DROP NOT"
                        + " NULL");
        String migration = Files.readString(Path.of("src/main/resources/db/migration",
                        "V67__logistics_carrier_import_queue.sql"))
                .replaceFirst("(?is)\\)\\s*ENGINE=InnoDB[^;]*;", ");")
                .replace(" JSON ", " LONGTEXT ").replace("UNIQUE KEY ", "CONSTRAINT ");
        for (String sql : migration.split(";")) {
            if (!sql.isBlank()) db.execute(sql.replace("uk_logistics_import_order (",
                    "uk_logistics_import_order UNIQUE ("));
        }
        for (String column : List.of(
                "source_channel VARCHAR(16) DEFAULT 'EXCEL'", "latest_source_json CLOB",
                "source_hash VARCHAR(64)", "latest_source_hash VARCHAR(64)",
                "source_difference INT DEFAULT 0", "last_synced_time TIMESTAMP",
                "bill_verification_json CLOB", "bill_history_json CLOB", "business_json CLOB")) {
            db.execute("ALTER TABLE order_logistics_import_order ADD " + column);
        }
        db.execute("CREATE ALIAS JSON_EXTRACT FOR '" + LogisticsImportPersistenceTest.class.getName() + ".jsonExtract'");
        db.execute("CREATE ALIAS JSON_UNQUOTE FOR '" + LogisticsImportPersistenceTest.class.getName() + ".jsonUnquote'");
        db.execute("""
                CREATE ALIAS HEX AS $$
                String mysqlHex(String value) {
                    return value == null ? null : java.util.HexFormat.of().withUpperCase()
                            .formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                $$
                """);
        db.execute("""
                CREATE TABLE order_logistics_transport_link (
                    tenant_id VARCHAR(64), source_id VARCHAR(36), order_type VARCHAR(32),
                    order_code VARCHAR(100), updated_by VARCHAR(50),
                    PRIMARY KEY(tenant_id,source_id,order_type,order_code))
                """);
        db.execute("""
                CREATE TABLE order_sales_shipment (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id VARCHAR(64),
                    connector_id VARCHAR(36), source_system_code VARCHAR(32),
                    source_document_no VARCHAR(128), sales_order_id BIGINT,
                    sales_order_no_snapshot VARCHAR(50), customer_code_snapshot VARCHAR(50),
                    customer_name_snapshot VARCHAR(200), warehouse_id BIGINT,
                    shipment_status_code VARCHAR(64), logistics_company VARCHAR(120),
                    tracking_no VARCHAR(120), ship_time TIMESTAMP, source_updated_at TIMESTAMP,
                    source_payload_json LONGTEXT, logistics_overrides LONGTEXT, deleted INT DEFAULT 0
                )
                """);
        db.execute("""
                CREATE TABLE order_sales_order (
                    id BIGINT, tenant_id VARCHAR(64), order_no VARCHAR(50),
                    order_status_code VARCHAR(32), deleted INT DEFAULT 0,
                    PRIMARY KEY(tenant_id,id)
                )
                """);
        db = new JdbcTemplate(ds);
        store = new JdbcShipmentTransportStore(db, json, platform, costs);
    }

    @AfterEach
    void close() throws Exception {
        keeper.close();
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
    void correctingShipmentUpdatesTheSameFeeAndRemainsAfterRepeatedSourceSync() {
        var source = sync("receivedin", 1);
        String costId = source.costId();
        var initial = json.readValue(db.queryForObject(
                "SELECT data_json FROM order_logistics_cost WHERE id=?", String.class, costId),
                LogisticsCostCommand.class);
        var priced = TransportAllocationPlan.reprice(initial, new BigDecimal("37.03"));
        String links = json.writeValueAsString(priced.ledger().sources());
        db.update("""
                UPDATE order_logistics_cost SET amount=37.03,amount_manually_edited=1,
                    data_json=?,sources_json=? WHERE id=?
                """, json.writeValueAsString(priced), links, costId);
        db.update("""
                INSERT INTO order_logistics_cost_attachment
                    (id,tenant_id,entry_id,file_key,file_name,content_type,file_size,created_by,created_time)
                VALUES('A1','T',?,'T/manual-receipt.pdf','签收凭证.pdf','application/pdf',50,'U',CURRENT_TIMESTAMP)
                """, costId);
        var time = Instant.parse("2026-11-30T17:24:00Z");
        var edit = new LogisticsEdit("JT", "极兔速递", List.of("JT456"), time, "送到前台");
        var sale = json.readValue("{\"documentNo\":\"SO001\"}", LogisticsCostSourceView.class);
        transactions.executeWithoutResult(status -> {
            store.updateLinkedShipment("T", "EDITOR", connector.toString(), "FH001", edit);
            db.update("""
                    INSERT INTO order_sales_shipment(tenant_id,connector_id,source_system_code,
                        source_document_no,logistics_overrides,deleted)
                    VALUES('T',?,'DINGHUOBAO','FH001',?,0)
                    """,
                    connector.toString(), json.writeValueAsString(edit));
        });
        String oldDocumentNo = db.queryForObject("SELECT document_no FROM order_logistics_cost", String.class);
        var audit = json.readTree(db.queryForObject(
                "SELECT snapshot_json FROM order_logistics_cost_audit WHERE entry_id=? AND action_code=?",
                String.class, costId, LogisticsCostAuditView.SHIPMENT_LOGISTICS_EDIT));
        assertThat(audit.path("shipmentNo").asText()).isEqualTo("FH001");
        assertThat(audit.path("trackingNumbers").get(0).asText()).isEqualTo("JT456");
        assertThat(audit.path("shipmentRemark").asText()).isEqualTo("送到前台");
        assertThat(audit.has("sourceFields")).isFalse();

        for (int version : List.of(2, 2, 3)) {
            transactions.executeWithoutResult(status -> {
                var next = store.withShipmentLogisticsEdit("T", command("receivedin", version));
                assertThat(next.shipTime()).isEqualTo(time);
                assertThat(next.trackingNumbers()).containsExactly("JT456");
                assertThat(next.sourceFields()).containsEntry("shipmentRemark", "送到前台");
                var draft = transport();
                var data = (tools.jackson.databind.node.ObjectNode) json.valueToTree(draft);
                data.put("costDate", "2026-12-01");
                draft = json.treeToValue(data, LogisticsCostCommand.class)
                        .withLedger(draft.ledger().withCarrierClassification("JT", "THIRD_PARTY")
                                .withPlatformOrderNo("JT456"), null);
                assertThat(store.sync("T", "SYSTEM", next, draft, sale, null).costId()).isEqualTo(costId);
            });
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class)).isEqualTo(1);
        var saved = db.queryForMap("SELECT * FROM order_logistics_cost WHERE id=?", costId);
        assertThat(saved).containsEntry("document_no", oldDocumentNo)
                .containsEntry("carrier_code", "JT").containsEntry("cost_month", "2026-12")
                .containsEntry("tracking_no", "JT456").containsEntry("sources_json", links);
        assertThat((BigDecimal) saved.get("amount")).isEqualByComparingTo("37.03");
        assertThat(saved.get("cost_date").toString()).isEqualTo("2026-12-01");
        var data = json.readValue(saved.get("data_json").toString(), LogisticsCostCommand.class);
        assertThat(data.ledger().shippingTime()).isEqualTo(LocalDateTime.parse("2026-12-01T01:24:00"));
        assertThat(data.ledger().sources().getFirst().orderCode()).isEqualTo("SO001");
        assertThat(db.queryForObject("SELECT file_key FROM order_logistics_cost_attachment WHERE entry_id=?",
                String.class, costId)).isEqualTo("T/manual-receipt.pdf");
        var lookup = new JdbcTransportTrackingLookup(db, json);
        assertThat(lookup.find("T", List.of("JT456"))).extracting(JdbcTransportTrackingLookup.Match::costId)
                .containsExactly(costId);
        assertThat(lookup.find("T", List.of("SF001"))).isEmpty();
    }

    @Test
    void correctingShipmentCannotTakeAnotherActiveCostsTrackingNumber() {
        var source = sync("receivedin", 1);
        var another = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 1));
        another.put("shipmentId", "D2");
        another.put("shipmentNo", "FH002");
        another.putArray("trackingNumbers").add("SF002");
        var second = store.sync("T", "U", json.treeToValue(another, Command.class), transport(), null, null);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                store.updateLinkedShipment("T", "U", connector.toString(), "FH001",
                        new LogisticsEdit(null, null, List.of("sf002"), null, null))))
                .hasMessageContaining("快递单号已有运输记录");
        var lookup = new JdbcTransportTrackingLookup(db, json);
        assertThat(lookup.find("T", List.of("SF001"))).extracting(JdbcTransportTrackingLookup.Match::costId)
                .containsExactly(source.costId());
        assertThat(lookup.find("T", List.of("SF002"))).extracting(JdbcTransportTrackingLookup.Match::costId)
                .containsExactly(second.costId());
    }

    @Test
    void cannotChangeSharedTrackingButCanEditTheShipmentTimeAndRemark() {
        var source = sync("receivedin", 1);
        db.update("UPDATE order_logistics_cost SET source_system='HUOLALA' WHERE id=?", source.costId());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                store.updateLinkedShipment("T", "U", connector.toString(), "FH001",
                        new LogisticsEdit(null, null, List.of("NEW"), null, null))))
                .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class)
                .hasMessageContaining("多个来源共用");
        var time = Instant.parse("2026-11-14T02:00:00Z");
        transactions.executeWithoutResult(status -> store.updateLinkedShipment("T", "U",
                connector.toString(), "FH001", new LogisticsEdit(null, null, null, time, "门卫代收")));
        var after = store.byCosts("T", List.of(source.costId())).get(source.costId()).getFirst();
        assertThat(after.latest().trackingNumbers()).containsExactly("SF001");
        assertThat(after.latest().shipTime()).isEqualTo(time);
        assertThat(after.latest().sourceFields()).containsEntry("shipmentRemark", "门卫代收");
        assertThat(db.queryForObject("SELECT cost_date FROM order_logistics_cost", String.class))
                .isEqualTo("2026-11-13");
    }

    @Test
    void employeeImmediateDeliveryUsesItsShipmentIdentityAndZeroCostWithoutCarrier() {
        var source = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 1));
        source.put("carrierName", "立即送");
        source.putNull("carrierCode");
        source.put("deliveryModeCode", "SELF");
        source.putArray("trackingNumbers");
        var incoming = json.treeToValue(source, Command.class);
        var draft = transport();
        draft = draft.withLedger(draft.ledger().withCarrierClassification(null, "SELF"), null);
        var sale = json.readValue("{\"documentNo\":\"SO001\"}", LogisticsCostSourceView.class);
        var first = store.sync("T", "U", incoming, draft, sale, null);
        assertThat(first.issue()).isNull();
        assertThat(store.sync("T", "U", incoming, draft, sale, null).costId()).isEqualTo(first.costId());
        source.put("shipmentId", "D2");
        source.put("shipmentNo", "FH002");
        assertThat(store.sync("T", "U", json.treeToValue(source, Command.class), draft, sale, null)
                .costId()).isNotEqualTo(first.costId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost WHERE amount=0"
                        + " AND delivery_mode_code='SELF' AND carrier_code IS NULL AND tracking_no IS NULL",
                Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT data_json FROM order_logistics_cost WHERE id=?",
                String.class, first.costId())).contains("员工配送，无外部运费");
    }

    @Test
    void shipmentsForTheSameSaleSharingAConfirmedTrackingNumberReuseItsCost() {
        var source = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 1));
        source.put("carrierCode", "JT");
        source.put("deliveryModeCode", "THIRD_PARTY");
        var draft = transport();
        draft = draft.withLedger(draft.ledger().withCarrierClassification("JT", "THIRD_PARTY"), null);
        var sale = json.readValue("{\"documentNo\":\"SO001\"}", LogisticsCostSourceView.class);
        var first = store.sync("T", "U", json.treeToValue(source, Command.class), draft, sale, null);
        db.update("UPDATE order_logistics_cost SET amount=37.03,amount_manually_edited=1 WHERE id=?",
                first.costId());
        source.put("shipmentId", "D2");
        source.put("shipmentNo", "FH002");
        var secondCommand = json.treeToValue(source, Command.class);
        assertThat(store.sync("T", "U", secondCommand, draft, sale, null).costId())
                .isEqualTo(first.costId());
        assertThat(store.sync("T", "U", secondCommand, draft, sale, null).costId())
                .isEqualTo(first.costId());
        assertThat(store.byCosts("T", List.of(first.costId())).get(first.costId())).hasSize(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT amount FROM order_logistics_cost", BigDecimal.class))
                .isEqualByComparingTo("37.03");
        assertThat(db.queryForObject("SELECT sources_json FROM order_logistics_cost", String.class))
                .contains("SO001");

        source.putArray("trackingNumbers").add("JT002");
        assertThat(store.sync("T", "U", json.treeToValue(source, Command.class), draft, sale, null)
                .differencePending()).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class)).isOne();
    }

    @Test
    void distinctShipmentsKeepTheirOwnCostsAndLinkToTheSameSalesOrder() {
        var first = syncShipment(store, "T", "1", List.of("JT001"));
        var second = syncShipment(store, "T", "2", List.of("JT002"));
        assertThat(first.costId()).isNotBlank().isNotEqualTo(second.costId());
        assertThat(second.costId()).isNotBlank();
        assertThat(syncShipment(store, "T", "1", List.of("JT001")).costId()).isEqualTo(first.costId());
        assertThat(syncShipment(store, "T", "2", List.of("JT002")).costId()).isEqualTo(second.costId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class)).isEqualTo(2);
        assertThat(db.queryForList("SELECT sources_json FROM order_logistics_cost", String.class))
                .allSatisfy(sources -> assertThat(sources).contains("SO001"));
    }

    @Test
    void repeatAndChangedSourceNeverCreateAnotherCostAndFreightRemainsUnknown() {
        var one = sync("receivedin", 1);
        String documentNumber =
                db.queryForObject("SELECT document_no FROM order_logistics_cost", String.class);
        assertThat(documentNumber).matches("CST[0-9]{23}");
        var two = sync("receivedin", 1);
        var three = sync("received", 2);
        assertThat(one.costId()).isEqualTo(two.costId()).isEqualTo(three.costId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT document_no FROM order_logistics_cost", String.class))
                .isEqualTo(documentNumber);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exactDhbShipmentReusesHuolalaCostAndPreservesFreight(boolean carrierProvided) {
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        var prior = receiveHuolala(imports, platformWriter, "142.64", "a");
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
        if (carrierProvided) {
            ((tools.jackson.databind.node.ObjectNode) data.path("ledger")).put("carrierCode", "HLL");
        } else {
            command.putNull("carrierName");
        }
        ((tools.jackson.databind.node.ObjectNode) data.path("ledger").path("sources").get(0))
                .set(
                        "allocations",
                        json.readTree(
                                "[{\"feeKey\":\"freight\",\"receivingPointCode\":\"TO0\",\"cityCode\":\"WH\",\"basis\":1,\"amount\":0}]"));
        var snapshot =
                json.readValue(
                        "{\"documentNo\":\"SO001\",\"sourceType\":\"SALES_ORDER\",\"cityCode\":\"WH\",\"employeeCode\":\"EMP1\"}",
                        LogisticsCostSourceView.class);
        var incoming = json.treeToValue(command, Command.class);
        var draft = json.treeToValue(data, LogisticsCostCommand.class);
        var result = store.sync("T", "U", incoming, draft, snapshot, null);
        assertThat(result.costId()).isEqualTo(prior.costId());
        assertThat(store.sync("T", "U", incoming, draft, snapshot, null).costId())
                .isEqualTo(prior.costId());
        if (!carrierProvided) {
            assertThat(result.issue()).isNull();
            assertThat(result.latest().carrierCode()).isEqualTo("HLL");
            assertThat(result.latest().deliveryModeCode()).isEqualTo("THIRD_PARTY");
        }
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
        assertThat(saved.getValue().ledger().sources().getFirst().orderCode()).isEqualTo("SO001");
        assertThat(saved.getValue().ledger().sources().getFirst().allocations().getFirst().amount())
                .isEqualByComparingTo("99");
        verify(platform).saveAssociations(eq("T"), eq(prior.costId()), any(), any(), eq("U"));
        verify(platform, never()).saveDraft(any(), any(), any(), any(), any());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void huolalaArrivingAfterDhbAdoptsSameCostAndKeepsSaleAndManualFreight(boolean manuallyEntered) {
        var dhb = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 1));
        dhb.putNull("carrierName");
        var draft = transport();
        var saved = store.sync("T", "U", json.treeToValue(dhb, Command.class),
                draft.withLedger(draft.ledger().withPlatformOrderNo("SF001"), null),
                json.readValue("{\"documentNo\":\"SO001\"}", LogisticsCostSourceView.class), null);
        var paid = new BigDecimal("99.00");
        var prior = json.readValue(db.queryForObject("SELECT data_json FROM order_logistics_cost",
                String.class), LogisticsCostCommand.class);
        // A manual save keeps the association in sources_json, separately from the command body.
        var priorAmount = manuallyEntered ? paid : null;
        if (manuallyEntered)
            prior = TransportAllocationPlan.reprice(prior, paid);
        var originalSource = json.valueToTree(prior.ledger().sources().getFirst());
        var body = (tools.jackson.databind.node.ObjectNode) json.valueToTree(prior);
        ((tools.jackson.databind.node.ObjectNode) body.path("ledger")).remove("sources");
        db.update("UPDATE order_logistics_cost SET amount=?,amount_manually_edited=?,data_json=?,sources_json=? WHERE id=?",
                priorAmount, manuallyEntered, json.writeValueAsString(body),
                json.writeValueAsString(prior.ledger().sources()), saved.costId());
        db.update("""
                INSERT INTO order_logistics_cost_attachment
                    (id,tenant_id,entry_id,file_key,file_name,content_type,file_size,created_by,created_time)
                VALUES('A1','T',?,'T/manual-receipt.pdf','手工回单.pdf','application/pdf',50,'U',CURRENT_TIMESTAMP)
                """, saved.costId());
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);

        assertThat(receiveHuolala(imports, platformWriter, "142.64", "a").costId())
                .isEqualTo(saved.costId());
        assertThat(db.queryForObject("SELECT amount FROM order_logistics_cost", BigDecimal.class))
                .isEqualByComparingTo(manuallyEntered ? "99.00" : "142.64");
        for (int attempt = 0; attempt < 2; attempt++) {
            var source = receiveHuolala(imports, platformWriter, "143.64", "b", "2026-11-14");
            assertThat(source.costId()).isEqualTo(saved.costId());
            assertThat(source.latestOrder().facts().receipts().getFirst().fileKey())
                    .isEqualTo("T/huolala-receipt.jpg");
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT amount FROM order_logistics_cost", BigDecimal.class))
                .isEqualByComparingTo(manuallyEntered ? "99.00" : "143.64");
        assertThat(db.queryForObject("SELECT file_key FROM order_logistics_cost_attachment"
                        + " WHERE entry_id=?", String.class, saved.costId()))
                .isEqualTo("T/manual-receipt.pdf");
        assertThat(db.queryForObject("SELECT carrier_code FROM order_logistics_cost", String.class))
                .isEqualTo("HLL");
        var sources = json.readTree(db.queryForObject("SELECT sources_json FROM order_logistics_cost",
                String.class));
        assertThat(sources.size()).isEqualTo(1);
        var source = sources.get(0);
        assertThat(source.path("orderCode").asText()).isEqualTo("SO001");
        assertThat(source.path("linkedAt")).isEqualTo(originalSource.path("linkedAt"));
        assertThat(source.path("allocations").get(0).path("amount").decimalValue())
                .isEqualByComparingTo(manuallyEntered ? "99.00" : "143.64");
    }

    @Test
    void dhbUpdatePreservesSalesSnapshotSavedSeparatelyFromTransportBody() {
        var saved = sync("receivedin", 1);
        var prior = json.readValue(db.queryForObject("SELECT data_json FROM order_logistics_cost",
                String.class), LogisticsCostCommand.class);
        var priced = TransportAllocationPlan.reprice(prior, new BigDecimal("64.00"));
        var body = (tools.jackson.databind.node.ObjectNode) json.valueToTree(priced);
        ((tools.jackson.databind.node.ObjectNode) body.path("ledger")).remove("sources");
        String originalSources = json.writeValueAsString(priced.ledger().sources());
        db.update("UPDATE order_logistics_cost SET amount=64,amount_manually_edited=1,data_json=?,sources_json=? WHERE id=?",
                json.writeValueAsString(body), originalSources, saved.costId());

        var shipment = (tools.jackson.databind.node.ObjectNode) json.valueToTree(transport());
        ((tools.jackson.databind.node.ObjectNode) shipment.path("ledger").path("sender"))
                .put("manualName", "更新的发货仓");
        var order = json.readValue(
                "{\"documentNo\":\"SO001\",\"documentAmount\":100,\"employeeCode\":\"EMP2\"}",
                LogisticsCostSourceView.class);
        var after = store.sync("T", "U", command("received", 2),
                json.treeToValue(shipment, LogisticsCostCommand.class), order, null);
        assertThat(after.costId()).isEqualTo(saved.costId());
        assertThat(db.queryForObject("SELECT amount FROM order_logistics_cost WHERE id=?",
                BigDecimal.class, saved.costId())).isEqualByComparingTo("64");
        assertThat(json.readTree(db.queryForObject(
                "SELECT sources_json FROM order_logistics_cost WHERE id=?", String.class, saved.costId())))
                .isEqualTo(json.readTree(originalSources));
        assertThat(json.readTree(db.queryForObject(
                "SELECT data_json FROM order_logistics_cost WHERE id=?", String.class, saved.costId()))
                .path("ledger").path("sender").path("manualName").asText()).isEqualTo("更新的发货仓");
    }

    @Test
    void fullTrackingNumberOccupiesOneCostAcrossShipmentsButNotAcrossTenants() {
        var one = syncShipment(store, "T", "1", List.of("A001", " 00sf002 "));
        var overlapping = syncShipment(store, "T", "2", List.of("00SF002", "SF003"));
        assertThat(overlapping.costId()).isNull();
        assertThat(overlapping.differencePending()).isTrue();
        assertThat(syncShipment(store, "T", "2", List.of("00SF002", "SF003")).costId()).isNull();
        assertThat(syncShipment(store, "T", "3", List.of("SF002")).costId()).isNotEqualTo(one.costId());
        assertThat(syncShipment(store, "OTHER", "1", List.of("00SF002")).costId()).isNotNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void huolalaCannotTakeOneNumberFromAnExistingMultiPackageShipment() {
        var shipment = syncShipment(store, "T", "1", List.of("SF001", "SF002"));
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        assertThatThrownBy(() -> transactions.execute(status -> {
            platformWriter.lockTenant("T");
            return receiveHuolala(imports, platformWriter, "142.64", "a");
        })).hasMessageContaining("快递单号已有运输记录");
        assertThat(db.queryForObject("SELECT id FROM order_logistics_cost", String.class))
                .isEqualTo(shipment.costId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_import_order", Integer.class))
                .isZero();
    }

    @Test
    void shipmentCannotReplaceItsTrackingWithAnotherRecordsNumber() {
        var one = syncShipment(store, "T", "1", List.of("SF001"));
        var two = syncShipment(store, "T", "2", List.of("SF002"));
        var conflicting = syncShipment(store, "T", "1", List.of("SF002"));
        assertThat(conflicting.costId()).isEqualTo(one.costId()).isNotEqualTo(two.costId());
        assertThat(conflicting.differencePending()).isTrue();
        assertThat(conflicting.latest().trackingNumbers()).containsExactly("SF001");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void concurrentSyncUsesOneCostRegardlessOfWhichSourceTakesTheLockFirst() throws Exception {
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        var shipmentWriter = new JdbcShipmentTransportStore(db, json, platformWriter, costs);
        usePersistedCostDetails();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var dhb = executor.submit(() -> {
                start.await();
                return transactions.execute(status -> syncShipment(shipmentWriter, "T", "1", List.of("SF001")));
            });
            var hll = executor.submit(() -> {
                start.await();
                return transactions.execute(status -> {
                    platformWriter.lockTenant("T");
                    return receiveHuolala(imports, platformWriter, "142.64", "a");
                });
            });
            start.countDown();
            assertThat(dhb.get(10, TimeUnit.SECONDS).costId())
                    .isEqualTo(hll.get(10, TimeUnit.SECONDS).costId()).isNotNull();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT sources_json FROM order_logistics_cost", String.class))
                .contains("SO001");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void distinctSalesOrdersShareOneHuolalaCostInBothSyncOrders(boolean huolalaFirst) {
        usePersistedCostDetails();
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        var shipmentWriter = new JdbcShipmentTransportStore(db, json, platformWriter, costs);
        var numbers = List.of("SF001");
        if (huolalaFirst) receiveHuolala(imports, platformWriter, "120", "a");
        var first = transactions.execute(status -> syncShipment(
                shipmentWriter, "T", "1", numbers, "SO001", "100", "WH"));
        var second = transactions.execute(status -> syncShipment(
                shipmentWriter, "T", "2", numbers, "SO002", "300", "SZ"));
        assertThat(first.costId()).isNotBlank();
        assertThat(second.costId()).isEqualTo(huolalaFirst ? first.costId() : null);
        var originalLink = json.readTree(db.queryForObject(
                "SELECT sources_json FROM order_logistics_cost", String.class)).get(0).path("linkedAt");
        var old = json.readValue(db.queryForObject("SELECT data_json FROM order_logistics_cost",
                String.class), LogisticsCostCommand.class);
        var manual = TransportAllocationPlan.reprice(old.withLedger(old.ledger(), null), new BigDecimal("100.00"));
        db.update("UPDATE order_logistics_cost SET amount=100,amount_manually_edited=1,data_json=?,sources_json=?",
                json.writeValueAsString(manual), json.writeValueAsString(manual.ledger().sources()));
        db.update("""
                INSERT INTO order_logistics_cost_attachment
                    (id,tenant_id,entry_id,file_key,file_name,content_type,file_size,created_by,created_time)
                VALUES('A1','T',?,'T/manual-receipt.pdf','手工回单.pdf','application/pdf',50,'U',CURRENT_TIMESTAMP)
                """, first.costId());

        for (int attempt = 0; attempt < 2; attempt++) {
            var source = transactions.execute(status -> {
                platformWriter.lockTenant("T");
                var row = receiveHuolala(imports, platformWriter, "150", "b");
                shipmentWriter.reconcileHuolala("T", "U", "SF001", row.costId());
                return row;
            });
            assertThat(source.costId()).isEqualTo(first.costId());
            assertThat(source.latestOrder().facts().receipts().getFirst().fileKey())
                    .isEqualTo("T/huolala-receipt.jpg");
            assertThat(transactions.execute(status -> syncShipment(
                    shipmentWriter, "T", "2", numbers, "SO002", "300", "SZ")).costId())
                    .isEqualTo(first.costId());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT amount FROM order_logistics_cost", BigDecimal.class))
                .isEqualByComparingTo("100.00");
        assertThat(db.queryForObject("SELECT file_key FROM order_logistics_cost_attachment", String.class))
                .isEqualTo("T/manual-receipt.pdf");
        assertThat(db.queryForList("SELECT cost_id FROM order_transport_shipment_source", String.class))
                .containsExactlyInAnyOrder(first.costId(), first.costId());
        var links = json.readTree(db.queryForObject("SELECT sources_json FROM order_logistics_cost", String.class));
        assertThat(links.size()).isEqualTo(2);
        assertThat(links.get(0).path("orderCode").asText()).isEqualTo("SO001");
        assertThat(links.get(0).path("linkedAt")).isEqualTo(originalLink);
        assertThat(links.get(0).path("allocations").get(0).path("amount").decimalValue())
                .isEqualByComparingTo("25.00");
        assertThat(links.get(1).path("orderCode").asText()).isEqualTo("SO002");
        assertThat(links.get(1).path("allocations").get(0).path("amount").decimalValue())
                .isEqualByComparingTo("75.00");
        assertThat(links.get(1).path("allocations").get(0).path("cityCode").asText()).isEqualTo("SZ");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void multiStopHuolalaLinksEveryShipmentWithoutChangingSavedLedger(boolean huolalaFirst) {
        usePersistedCostDetails();
        var expectedOrders = new ArrayList<String>();
        for (int i = 1; i <= 8; i++) {
            String orderNo = "SO00" + i;
            expectedOrders.add(orderNo);
            db.update("INSERT INTO order_sales_order(id,tenant_id,order_no,order_status_code) VALUES(?,'T',?,'SUBMITTED')",
                    10L + i, orderNo);
        }
        db.update("INSERT INTO order_sales_order(id,tenant_id,order_no,order_status_code) VALUES(12,'OTHER','OTHER-SALE','SUBMITTED')");
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        var shipmentWriter = new JdbcShipmentTransportStore(db, json, platformWriter, costs);
        var tracking = List.of("SF001");
        if (!huolalaFirst) {
            var dhb = transactions.execute(status -> syncShipment(
                    shipmentWriter, "T", "1", tracking, "SO001", "100", "WH", 11L));
            assertThat(dhb.costId()).isNotBlank();
            var data = json.readValue(db.queryForObject(
                    "SELECT data_json FROM order_logistics_cost WHERE id=?", String.class, dhb.costId()),
                    LogisticsCostCommand.class);
            var manual = TransportAllocationPlan.reprice(data, new BigDecimal("87.00"));
            var manualJson = (tools.jackson.databind.node.ObjectNode) json.valueToTree(manual);
            manualJson.put("paymentStatusCode", "PAID");
            ((tools.jackson.databind.node.ObjectNode) manualJson.path("ledger").path("feeItems").get(0))
                    .put("allocationMethodCode", "MANUAL");
            db.update("UPDATE order_logistics_cost SET amount=87,amount_manually_edited=1,payment_status_code='PAID',data_json=?,sources_json=? WHERE id=?",
                    json.writeValueAsString(manualJson), json.writeValueAsString(manual.ledger().sources()), dhb.costId());
            assertThat(transactions.execute(status -> syncShipment(
                    shipmentWriter, "T", "2", tracking, "SO002", "300", "SZ", 12L)).costId()).isNull();
        }
        var platformRow = transactions.execute(status -> {
            platformWriter.lockTenant("T");
            return receiveMultiStopHuolala(imports, platformWriter);
        });
        String id = platformRow.costId();
        var before = financialSnapshot(id);
        transactions.executeWithoutResult(status -> {
            platformWriter.lockTenant("T");
            shipmentWriter.reconcileHuolala("T", "U", "SF001", id);
        });
        for (int replay = 0; replay < 2; replay++) {
            for (int i = 0; i < expectedOrders.size(); i++) {
                String orderNo = expectedOrders.get(i);
                long salesId = 11L + i;
                var linked = transactions.execute(status -> syncShipment(
                        shipmentWriter, "T", Long.toString(salesId - 10), tracking,
                        orderNo, "100", salesId == 11L ? "WH" : "SZ", salesId));
                assertThat(linked.costId()).isEqualTo(id);
                assertThat(linked.issue()).isNull();
            }
        }
        assertThat(financialSnapshot(id)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class)).isOne();
        assertThat(shipmentWriter.byCosts("T", List.of(id)).get(id))
                .extracting(Source::salesOrderNo).containsExactlyInAnyOrderElementsOf(expectedOrders);
    }

    @Test
    void repeatedHuolalaSyncRelinksOnlyVerifiedHistoricalAllocationConflicts() {
        usePersistedCostDetails();
        db.update("""
                INSERT INTO order_sales_order(id,tenant_id,order_no,order_status_code)
                VALUES(11,'T','SO001','COMPLETED'),(12,'T','SO002','CANCELLED')
                """);
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        var shipmentWriter = new JdbcShipmentTransportStore(db, json, platformWriter, costs);
        var platformRow = transactions.execute(status -> {
            platformWriter.lockTenant("T");
            return receiveMultiStopHuolala(imports, platformWriter);
        });
        String id = platformRow.costId();
        var before = financialSnapshot(id);
        var prior = transactions.execute(status -> syncShipment(
                shipmentWriter, "T", "1", List.of("SF001"), "SO001", "100", "WH", 11L));
        String issue = "已有费用明细或多个收货点，请核对后关联销售订单";
        db.update("UPDATE order_transport_shipment_source SET cost_id=NULL,issue=?,difference_pending=1 WHERE id=?",
                issue, prior.id());
        var conflicting = transactions.execute(status -> syncShipment(
                shipmentWriter, "T", "2", List.of("SF001"), "SO002", "300", "SZ", 12L));
        assertThat(conflicting.costId()).isNull();
        assertThat(conflicting.issue()).isEqualTo(issue);
        for (int replay = 0; replay < 2; replay++) {
            transactions.executeWithoutResult(status -> {
                platformWriter.lockTenant("T");
                shipmentWriter.reconcileHuolala("T", "U", "SF001", id);
            });
        }
        assertThat(db.queryForObject("SELECT cost_id FROM order_transport_shipment_source WHERE id=?",
                String.class, prior.id())).isEqualTo(id);
        assertThat(db.queryForObject("SELECT cost_id FROM order_transport_shipment_source WHERE id=?",
                String.class, conflicting.id())).isNull();
        assertThat(shipmentWriter.byCosts("T", List.of(id)).get(id))
                .extracting(Source::salesOrderNo).containsExactly("SO001");
        assertThat(financialSnapshot(id)).isEqualTo(before);
    }

    @Test
    void reconcilesEightMultiStopShipmentsIncludingSnapshotsWithoutSalesOrders() {
        usePersistedCostDetails();
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        var shipmentWriter = new JdbcShipmentTransportStore(db, json, platformWriter, costs);
        String costId = transactions.execute(status -> {
            platformWriter.lockTenant("T");
            return receiveMultiStopHuolala(imports, platformWriter).costId();
        });

        var existingSourceIds = new ArrayList<String>();
        for (int i = 1; i <= 8; i++) {
            String orderNo = "SO00" + i;
            String shipmentId = Integer.toString(3858800 + i);
            String shipmentNo = "FH" + i;
            seedShipmentSnapshot("T", shipmentId, shipmentNo, orderNo, "SF001",
                    i == 8 ? "SHIPPED" : "SIGNED");
            if (i <= 5) {
                long saleId = 10L + i;
                db.update("""
                        INSERT INTO order_sales_order(id,tenant_id,order_no,order_status_code)
                        VALUES(?,'T',?,'SUBMITTED')
                        """, saleId, orderNo);
                var source = transactions.execute(status -> syncShipment(
                        shipmentWriter, "T", shipmentId, List.of("SF001"), orderNo, "234", "WH",
                        saleId));
                existingSourceIds.add(source.id());
                var aligned = (tools.jackson.databind.node.ObjectNode) json.valueToTree(source.latest());
                aligned.put("shipmentId", shipmentId).put("shipmentNo", shipmentNo);
                aligned.set("sourceFields", shipmentPayload(
                        shipmentId, shipmentNo, orderNo, "SF001", "SIGNED"));
                db.update("""
                        UPDATE order_transport_shipment_source
                        SET source_shipment_id=?,source_shipment_no=?,latest_json=? WHERE id=?
                        """, shipmentId, shipmentNo, json.writeValueAsString(aligned), source.id());
                if (i != 1) {
                    db.update("""
                            UPDATE order_transport_shipment_source SET cost_id=NULL,
                                issue='已有费用明细或多个收货点，请核对后关联销售订单',
                                difference_pending=1
                            WHERE id=?
                            """, source.id());
                }
            }
        }
        seedShipmentSnapshot("T", "3858809", "FH9", "CANCELLED-SALE", "SF001", "CANCELLED");
        seedShipmentSnapshot("T", "3858810", "FH10", "PARTIAL-TRACKING", "SF0019", "SIGNED");
        seedShipmentSnapshot("T", "3858811", "FH11", "MULTIPLE-TRACKINGS", "SF001,SF002", "SIGNED");
        seedShipmentSnapshot("OTHER", "3858812", "FH12", "OTHER-SALE", "SF001", "SIGNED");

        var before = financialSnapshot(costId);
        Integer repaired = transactions.execute(status -> shipmentWriter.reconcileShipmentOrders(
                "T", "U", costId));
        assertThat(repaired).isEqualTo(7);
        var repairTimes = shipmentWriter.repairedShipmentLinkTimes("T", costId);
        assertThat(repairTimes).containsOnlyKeys("FH2", "FH3", "FH4", "FH5", "FH6", "FH7", "FH8");
        assertThat(repairTimes.values()).doesNotContainNull();
        assertThat(shipmentWriter.repairedShipmentLinkTimes("OTHER", costId)).isEmpty();
        Integer repeated = transactions.execute(status -> shipmentWriter.reconcileShipmentOrders(
                "T", "U", costId));
        assertThat(repeated).isZero();
        assertThat(shipmentWriter.repairedShipmentLinkTimes("T", costId)).isEqualTo(repairTimes);

        var sources = shipmentWriter.byCosts("T", List.of(costId)).get(costId);
        assertThat(sources).hasSize(8);
        assertThat(sources).extracting(Source::id).containsAll(existingSourceIds);
        assertThat(sources).allMatch(source -> source.issue() == null && !source.differencePending());
        assertThat(sources).extracting(source -> source.latest().shipmentNo())
                .containsExactlyInAnyOrder("FH1", "FH2", "FH3", "FH4", "FH5", "FH6", "FH7", "FH8");
        assertThat(sources).filteredOn(source -> source.latest().salesOrderId() != null)
                .extracting(Source::salesOrderNo)
                .containsExactlyInAnyOrder("SO001", "SO002", "SO003", "SO004", "SO005");
        assertThat(sources).filteredOn(source -> source.latest().salesOrderId() == null)
                .allMatch(source -> source.salesOrderNo() == null)
                .extracting(Source::sourceSalesOrderNo)
                .containsExactlyInAnyOrder("SO006", "SO007", "SO008");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sales_order", Integer.class)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class)).isOne();
        assertThat(financialSnapshot(costId)).isEqualTo(before);
    }

    @Test
    void reconciliationLeavesConflictingHistoricalSalesIdentitiesPending() {
        usePersistedCostDetails();
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        var shipmentWriter = new JdbcShipmentTransportStore(db, json, platformWriter, costs);
        String costId = transactions.execute(status -> {
            platformWriter.lockTenant("T");
            return receiveMultiStopHuolala(imports, platformWriter).costId();
        });
        db.update("""
                INSERT INTO order_sales_order(id,tenant_id,order_no,order_status_code)
                VALUES(11,'T','DIFFERENT-SALE','COMPLETED'),(12,'T','SO002','COMPLETED')
                """);
        for (int i = 1; i <= 2; i++) {
            String shipmentId = Integer.toString(3858800 + i);
            String shipmentNo = "FH" + i;
            seedShipmentSnapshot("T", shipmentId, shipmentNo, "SO00" + i, "SF001", "SIGNED");
            var latest = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("received", 1));
            latest.put("shipmentId", shipmentId).put("shipmentNo", shipmentNo);
            latest.put("carrierCode", "HLL").put("deliveryModeCode", "THIRD_PARTY");
            if (i == 2) latest.putNull("salesOrderId");
            db.update("""
                    INSERT INTO order_transport_shipment_source(
                        id,tenant_id,platform_code,connector_id,source_shipment_id,source_shipment_no,
                        sales_order_id,latest_json,fingerprint,issue,difference_pending,created_by,updated_by)
                    VALUES(?,'T','DHB',?,?,?,?,?,?,'已有费用明细或多个收货点，请核对后关联销售订单',1,'U','U')
                    """, UUID.randomUUID().toString(), connector.toString(), shipmentId, shipmentNo,
                    10L + i, json.writeValueAsString(latest), "a".repeat(64));
        }
        var before = financialSnapshot(costId);
        Integer repaired = transactions.execute(status -> shipmentWriter.reconcileShipmentOrders(
                "T", "U", costId));
        assertThat(repaired).isZero();
        assertThat(shipmentWriter.byCosts("T", List.of(costId))).isEmpty();
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM order_transport_shipment_source
                WHERE tenant_id='T' AND cost_id IS NULL AND difference_pending=1
                    AND issue='已有费用明细或多个收货点，请核对后关联销售订单'
                """, Integer.class)).isEqualTo(2);
        assertThat(financialSnapshot(costId)).isEqualTo(before);
    }

    private void seedShipmentSnapshot(
            String tenant, String id, String shipmentNo, String orderNo, String tracking, String status) {
        db.update("""
                INSERT INTO order_sales_shipment(tenant_id,connector_id,source_system_code,
                    source_document_no,sales_order_no_snapshot,shipment_status_code,logistics_company,
                    tracking_no,ship_time,source_updated_at,source_payload_json)
                VALUES(?,?,'DINGHUOBAO',?,?,?,'货拉拉',?,
                    TIMESTAMP '2026-11-13 01:00:00',TIMESTAMP '2026-11-13 02:00:00',?)
                """, tenant, connector.toString(), shipmentNo, orderNo, status, tracking,
                json.writeValueAsString(shipmentPayload(id, shipmentNo, orderNo, tracking, status)));
    }

    private tools.jackson.databind.JsonNode shipmentPayload(
            String id, String shipmentNo, String orderNo, String tracking, String status) {
        var payload = json.createObjectNode();
        payload.putObject("detail")
                .put("ships_id", Long.parseLong(id))
                .put("ships_num", shipmentNo)
                .put("type_id", 10)
                .put("orders_num", orderNo)
                .put("express_num", tracking)
                .put("logistics_name", "货拉拉")
                .put("status", switch (status) {
                    case "SIGNED" -> "received";
                    case "SHIPPED" -> "receivedin";
                    default -> "cancelled";
                });
        return payload;
    }

    @Test
    void listSnapshotCannotAllocateBeforeMultiStopHuolalaDetailArrives() {
        usePersistedCostDetails();
        db.update("INSERT INTO order_sales_order(id,tenant_id,order_no,order_status_code) VALUES(11,'T','SO001','COMPLETED')");
        var imports = new JdbcLogisticsImportStore(db, json);
        var platformWriter = new JdbcPlatformTransportStore(db, json, imports);
        var shipmentWriter = new JdbcShipmentTransportStore(db, json, platformWriter, costs);
        var summary = json.readValue("""
                {"enterpriseId":"AC1","orderNo":"SF001","status":"已完成","line":0,
                 "useTime":"2026-11-13 09:00:00","raw":{"详情状态":"LIST"},"facts":{"billAmount":120}}
                """, CarrierOrder.class);
        var platformRow = transactions.execute(status -> {
            platformWriter.lockTenant("T");
            return platformWriter.materialize("T", "U",
                    imports.receive("T", "U", new ApiSource("HLL-CONNECTOR", "b".repeat(64), summary)));
        });
        String id = platformRow.costId();
        var before = financialSnapshot(id);
        var awaiting = transactions.execute(status -> syncShipment(
                shipmentWriter, "T", "1", List.of("SF001"), "SO001", "100", "WH", 11L));
        assertThat(awaiting.costId()).isNull();
        assertThat(awaiting.issue()).contains("等待货拉拉订单详情");
        transactions.executeWithoutResult(status -> {
            platformWriter.lockTenant("T");
            shipmentWriter.reconcileHuolala("T", "U", "SF001", id);
        });
        assertThat(db.queryForObject("SELECT awaiting_huolala_no FROM order_transport_shipment_source WHERE id=?",
                String.class, awaiting.id())).isEqualTo("SF001");
        assertThat(financialSnapshot(id)).isEqualTo(before);
        transactions.executeWithoutResult(status -> {
            platformWriter.lockTenant("T");
            var detail = receiveMultiStopHuolala(imports, platformWriter);
            assertThat(detail.costId()).isEqualTo(id);
            shipmentWriter.reconcileHuolala("T", "U", "SF001", id);
        });
        assertThat(shipmentWriter.byCosts("T", List.of(id)).get(id))
                .extracting(Source::salesOrderNo).containsExactly("SO001");
        assertThat(db.queryForObject("SELECT awaiting_huolala_no FROM order_transport_shipment_source WHERE id=?",
                String.class, awaiting.id())).isNull();
        assertThat(financialSnapshot(id)).isEqualTo(before);
    }

    private String financialSnapshot(String id) {
        return db.queryForObject("""
                SELECT revision,status_code,amount,payment_status_code,carrier_code,delivery_mode_code,
                       data_json,sources_json
                FROM order_logistics_cost WHERE id=?
                """, (row, index) -> json.writeValueAsString(Arrays.asList(
                        row.getInt("revision"), row.getString("status_code"), row.getBigDecimal("amount"),
                        row.getString("payment_status_code"), row.getString("carrier_code"),
                        row.getString("delivery_mode_code"), row.getString("data_json"),
                        row.getString("sources_json"))), id);
    }

    private void usePersistedCostDetails() {
        when(costs.entry(eq("T"), eq("LOGISTICS"), anyString(), anyString()))
                .thenAnswer(call -> {
                    String costId = call.getArgument(2);
                    var saved = db.queryForMap("SELECT revision,amount,data_json,sources_json FROM order_logistics_cost WHERE id=?",
                            costId);
                    var data = (tools.jackson.databind.node.ObjectNode) json.readTree(saved.get("data_json").toString());
                    if (data.path("ledger").isObject()) {
                        ((tools.jackson.databind.node.ObjectNode) data.path("ledger")).set(
                                "sources", json.readTree(saved.get("sources_json").toString()));
                    }
                    var detail = json.createObjectNode().put("id", costId).put("revision", ((Number) saved.get("revision")).intValue())
                            .put("statusCode", "ACTIVE");
                    detail.set("data", data);
                    detail.putArray("sources");
                    return json.treeToValue(detail, LogisticsCostDetailView.class);
                });
        doAnswer(call -> {
            LogisticsCostCommand data = call.getArgument(3);
            db.update("UPDATE order_logistics_cost SET data_json=?,sources_json=?,revision=revision+1"
                            + " WHERE tenant_id=? AND id=?",
                    json.writeValueAsString(data), json.writeValueAsString(data.ledger().sources()),
                    call.getArgument(0), call.getArgument(2));
            return null;
        }).when(costs).save(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    private Source syncShipment(JdbcShipmentTransportStore writer, String tenant, String suffix, List<String> tracking) {
        return syncShipment(writer, tenant, suffix, tracking, "SO001", "100", "WH");
    }

    private Source syncShipment(JdbcShipmentTransportStore writer, String tenant, String suffix,
                                List<String> tracking, String orderNo, String amount, String city) {
        return syncShipment(writer, tenant, suffix, tracking, orderNo, amount, city, 11L);
    }

    private Source syncShipment(JdbcShipmentTransportStore writer, String tenant, String suffix,
                                List<String> tracking, String orderNo, String amount, String city, long salesId) {
        var source = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 1));
        source.put("shipmentId", "D" + suffix);
        source.put("shipmentNo", "FH" + suffix);
        source.put("salesOrderId", salesId);
        source.putNull("carrierName");
        source.set("trackingNumbers", json.valueToTree(tracking));
        var data = json.valueToTree(transport());
        ((tools.jackson.databind.node.ObjectNode) data.path("ledger").path("sources").get(0))
                .put("orderCode", orderNo);
        var draft = json.treeToValue(data, LogisticsCostCommand.class);
        return writer.sync(tenant, "U", json.treeToValue(source, Command.class),
                draft.withLedger(draft.ledger().withPlatformOrderNo(tracking.getFirst()), null),
                json.readValue("""
                        {"documentNo":"%s","documentAmount":%s,"cityCode":"%s","sourceType":"SALES_ORDER"}
                        """.formatted(orderNo, amount, city), LogisticsCostSourceView.class), null);
    }

    private Row receiveHuolala(JdbcLogisticsImportStore imports, JdbcPlatformTransportStore writer,
                               String amount, String hash) {
        return receiveHuolala(imports, writer, amount, hash, "2026-11-13");
    }

    private Row receiveHuolala(JdbcLogisticsImportStore imports, JdbcPlatformTransportStore writer,
                               String amount, String hash, String date) {
        var huolala = json.readValue("""
                {"enterpriseId":"AC1","orderNo":"SF001","status":"已完成","line":0,
                 "useTime":"%s 09:00:00","raw":{},"facts":{"billAmount":%s,
                 "receipts":[{"id":"PHOTO1","name":"回单.jpg","fileKey":"T/huolala-receipt.jpg","state":"READY"}]}}
                """.formatted(date, amount), CarrierOrder.class);
        return writer.materialize("T", "U",
                imports.receive("T", "U", new ApiSource("HLL-CONNECTOR", hash.repeat(64), huolala)));
    }

    private Row receiveMultiStopHuolala(
            JdbcLogisticsImportStore imports, JdbcPlatformTransportStore writer) {
        var huolala = json.readValue("""
                {"enterpriseId":"AC1","orderNo":"SF001","status":"已完成","line":0,
                 "useTime":"2026-11-13 09:00:00","raw":{"详情状态":"DETAIL"},"facts":{"billAmount":120,
                 "stops":[{"name":"发货仓"},{"name":"收货点一"},{"name":"收货点二"}]}}
                """, CarrierOrder.class);
        return writer.materialize("T", "U",
                imports.receive("T", "U", new ApiSource("HLL-CONNECTOR", "a".repeat(64), huolala)));
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
        var entered = (tools.jackson.databind.node.ObjectNode) json.readTree(
                db.queryForObject("SELECT data_json FROM order_logistics_cost WHERE id=?", String.class, first.costId()));
        entered.put("amount", new java.math.BigDecimal("64.00"));
        entered.put("paymentStatusCode", "PAID");
        entered.put("remark", "业务补录运费与凭证后关联原销售单");
        var ledger = (tools.jackson.databind.node.ObjectNode) entered.path("ledger");
        ledger.set("manualBillItems", json.readTree("[{\"name\":\"基础运费\",\"amount\":54},{\"name\":\"装卸费\",\"amount\":10}]"));
        var fee = (tools.jackson.databind.node.ObjectNode) ledger.path("feeItems").get(0);
        fee.put("feeAmount",64); fee.put("ourAmount",64);
        db.update("UPDATE order_logistics_cost SET amount=64,payment_status_code='PAID',data_json=? WHERE id=?",json.writeValueAsString(entered),first.costId());
        var source =
                json.readValue(
                        "{\"documentNo\":\"SO001\",\"documentAmount\":100,\"employeeCode\":\"EMP1\",\"departmentId\":7}",
                        LogisticsCostSourceView.class);
        var next = store.sync("T", "U", command("receivedin", 2), transport(), source, null);
        assertThat(next.costId()).isEqualTo(first.costId());
        assertThat(store.sync("T", "U", command("receivedin", 2), transport(), source, null).costId()).isEqualTo(first.costId());
        assertThat(store.sync("T", "U", command("receivedin", 3), transport(), source, null).costId()).isEqualTo(first.costId());
        var saved = json.readValue(db.queryForObject("SELECT data_json FROM order_logistics_cost WHERE id=?",String.class,first.costId()),LogisticsCostCommand.class);
        assertThat(saved.amount()).isEqualByComparingTo("64");
        assertThat(saved.paymentStatusCode()).isEqualTo("PAID");
        assertThat(saved.remark()).isEqualTo("业务补录运费与凭证后关联原销售单");
        assertThat(saved.ledger().manualBillItems()).hasSize(2);
        assertThat(saved.ledger().sources().getFirst().allocations().stream().map(LogisticsCostLedger.Allocation::amount).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add)).isEqualByComparingTo("64");
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
