package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.api.v1.model.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

class SalesShipmentSnapshotPersistenceTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    JdbcSalesShipmentSnapshotStore store;
    UUID connector = UUID.randomUUID();

    @BeforeEach
    void setup() {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE order_sales_shipment_sync_guard(tenant_id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute(
                "CREATE TABLE order_sales_shipment(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id"
                    + " VARCHAR(64),shipment_no VARCHAR(50),connector_id"
                    + " VARCHAR(36),source_system_code VARCHAR(64),source_document_no"
                    + " VARCHAR(128),sales_order_no_snapshot VARCHAR(50),customer_code_snapshot"
                    + " VARCHAR(50),customer_name_snapshot VARCHAR(200),contact_phone_snapshot"
                    + " VARCHAR(50),source_warehouse_name VARCHAR(200),shipment_status_code"
                    + " VARCHAR(64),logistics_company VARCHAR(120),tracking_no"
                    + " VARCHAR(120),ship_time TIMESTAMP,stock_out_time TIMESTAMP,source_updated_at"
                    + " TIMESTAMP,source_payload_json LONGTEXT,total_quantity"
                    + " DECIMAL(20,6),revision INT,created_by VARCHAR(50),created_time"
                    + " TIMESTAMP,updated_by VARCHAR(50),updated_time TIMESTAMP,deleted"
                    + " INT,UNIQUE(tenant_id,connector_id,source_system_code,source_document_no),UNIQUE(tenant_id,shipment_no))");
        store = new JdbcSalesShipmentSnapshotStore(jdbc, JsonMapper.builder().build());
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    SalesShipmentSnapshotCommand command(String status, String tracking, Instant version) {
        return new SalesShipmentSnapshotCommand(
                connector,
                "FH1",
                "DH1",
                "C1",
                "来源客户",
                "123",
                "来源仓库",
                status,
                "顺丰",
                tracking,
                null,
                Instant.parse("2026-11-16T03:04:05Z"),
                version,
                Map.of(
                        "detail",
                        Map.of(
                                "list",
                                List.of(
                                        Map.of(
                                                "goods_name",
                                                "原商品",
                                                "ships_number",
                                                3,
                                                "options_info",
                                                List.of(Map.of("options_name", "红色")),
                                                "orders_list_info",
                                                Map.of("base_units_name", "件"))),
                                "status",
                                status,
                                "express_num",
                                tracking)));
    }

    @Test
    void missingAllLocalMappingsAndTrackingStillPreservesFactsAndIdempotency() {
        var c = command("CREATED", "", Instant.parse("2026-11-16T03:00:00Z"));
        var id = store.save("T", c);
        var again = store.save("T", c);
        assertThat(again).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_sales_shipment", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM order_sales_shipment", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT ship_time FROM order_sales_shipment", Object.class))
                .isNull();
        var lines =
                SalesShipmentSnapshotFacts.lines(
                        jdbc.queryForObject(
                                "SELECT source_payload_json FROM order_sales_shipment",
                                String.class));
        assertThat(lines.getFirst().shippedQuantity()).isEqualByComparingTo("3");
        assertThat(lines.getFirst().specificationSnapshot()).isEqualTo("红色");
        assertThat(lines.getFirst().unitCode()).isEqualTo("件");
    }

    @Test
    void detailFailureCannotClearPreviouslyRetainedDetails() {
        var complete = command("SHIPPED", "SF123", Instant.parse("2026-11-16T04:00:00Z"));
        var id = store.save("T", complete);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("list", Map.of("status", "received"));
        payload.put("detail", null);
        var summary =
                new SalesShipmentSnapshotCommand(
                        connector,
                        "FH1",
                        "DH1",
                        null,
                        null,
                        null,
                        null,
                        "SIGNED",
                        null,
                        null,
                        null,
                        null,
                        Instant.parse("2026-11-16T05:00:00Z"),
                        payload);
        assertThat(store.save("T", summary)).isEqualTo(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT tracking_no FROM order_sales_shipment", String.class))
                .isEqualTo("SF123");
        assertThat(
                        SalesShipmentSnapshotFacts.lines(
                                jdbc.queryForObject(
                                        "SELECT source_payload_json FROM order_sales_shipment",
                                        String.class)))
                .hasSize(1);
    }

    @Test
    void changedFactsUpdateSameRowStaleIgnoredAndTenantConnectorIsolated() {
        var newer = command("SHIPPED", "SF123", Instant.parse("2026-11-16T04:00:00Z"));
        var id = store.save("T", newer);
        store.save("T", command("CREATED", "", Instant.parse("2026-11-16T03:00:00Z")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT tracking_no FROM order_sales_shipment WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("SF123");
        assertThat(store.save("OTHER", newer)).isNotEqualTo(id);
        connector = UUID.randomUUID();
        assertThat(
                        store.save(
                                "T",
                                command("SIGNED", "SF456", Instant.parse("2026-11-16T05:00:00Z"))))
                .isNotEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_sales_shipment", Integer.class))
                .isEqualTo(3);
    }
}
