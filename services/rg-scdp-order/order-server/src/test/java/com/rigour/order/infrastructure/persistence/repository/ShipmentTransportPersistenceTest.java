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
                CREATE TABLE order_logistics_transport_link (
                    tenant_id VARCHAR(64), source_id VARCHAR(36), order_type VARCHAR(32),
                    order_code VARCHAR(100), updated_by VARCHAR(50),
                    PRIMARY KEY(tenant_id,source_id,order_type,order_code))
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
        var source = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command("receivedin", 1));
        source.put("shipmentId", "D" + suffix);
        source.put("shipmentNo", "FH" + suffix);
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
