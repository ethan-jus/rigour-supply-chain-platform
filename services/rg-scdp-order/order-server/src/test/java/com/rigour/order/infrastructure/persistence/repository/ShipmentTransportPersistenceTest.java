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
    void waitingIsNotShippedAndCancellationCannotOverwriteConfirmedMoneyOrBusinessSnapshot() {
        assertThat(sync("shipped", 1).costId()).isNull();
        var source = sync("receivedin", 2);
        db.update("UPDATE order_logistics_cost SET status_code='CONFIRMED',amount=137.64");
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
}
