package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.SalesShipmentSnapshotCommand;
import com.rigour.order.api.v1.model.ShipmentTransportModels.LogisticsEdit;
import com.rigour.order.application.port.out.SalesShipmentSnapshotStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcSalesShipmentSnapshotStore implements SalesShipmentSnapshotStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rigour.shared.cache.QueryCache cache =
            com.rigour.shared.cache.QueryCache.disabled();

    public JdbcSalesShipmentSnapshotStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public Long save(String tenant, SalesShipmentSnapshotCommand c) {
        jdbc.update(
                "INSERT INTO order_sales_shipment_sync_guard(tenant_id) VALUES(?) ON DUPLICATE KEY"
                        + " UPDATE tenant_id=tenant_id",
                tenant);
        jdbc.queryForObject(
                "SELECT tenant_id FROM order_sales_shipment_sync_guard WHERE tenant_id=? FOR"
                        + " UPDATE",
                String.class,
                tenant);
        var existing =
                jdbc.query(
                        "SELECT id,source_updated_at,source_payload_json,logistics_overrides FROM"
                                + " order_sales_shipment WHERE tenant_id=? AND connector_id=? AND"
                                + " source_system_code='DINGHUOBAO' AND source_document_no=? FOR UPDATE",
                        (r, n) -> {
                            // MySQL DATETIME 的默认 Object 类型随驱动配置变化，显式读取时间类型。
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", r.getLong("id"));
                            row.put("source_updated_at", r.getTimestamp("source_updated_at"));
                            row.put("source_payload_json", r.getString("source_payload_json"));
                            row.put("logistics_overrides", r.getString("logistics_overrides"));
                            return row;
                        },
                        tenant,
                        c.connectorId().toString(),
                        c.shipmentNo());
        if (!existing.isEmpty()) {
            var old = existing.getFirst();
            var saved = json.readTree(Objects.toString(old.get("source_payload_json"), "null"));
            // 详情暂时获取失败时，只为尚未留存的单据保存列表事实。
            // 已有完整详情必须保留，不能被缺少商品和收发货资料的列表摘要清空。
            if (!(c.payload().get("detail") instanceof Map<?, ?>)
                    && saved.path("detail").isObject()) return ((Number) old.get("id")).longValue();
            var time = (Timestamp) old.get("source_updated_at");
            if (time != null
                    && (c.sourceUpdatedAt() == null
                            || c.sourceUpdatedAt().isBefore(time.toInstant())))
                return ((Number) old.get("id")).longValue();
            if (Objects.equals(saved, json.valueToTree(c.payload())))
                return ((Number) old.get("id")).longValue();
        }
        var now = Timestamp.from(Instant.now());
        if (existing.isEmpty())
            jdbc.update(
                    "INSERT INTO"
                        + " order_sales_shipment(tenant_id,shipment_no,connector_id,source_system_code,source_document_no,shipment_status_code,total_quantity,revision,created_by,created_time,updated_by,updated_time,deleted)"
                        + " VALUES(?,?,?,'DINGHUOBAO',?,'CREATED',0,1,'SYSTEM',?,'SYSTEM',?,0)",
                    tenant,
                    "DHB-"
                            + UUID.nameUUIDFromBytes(
                                    (c.connectorId() + ":" + c.shipmentNo())
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    c.connectorId().toString(),
                    c.shipmentNo(),
                    now,
                    now);
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM order_sales_shipment WHERE tenant_id=? AND connector_id=?"
                                + " AND source_system_code='DINGHUOBAO' AND source_document_no=?",
                        Long.class,
                        tenant,
                        c.connectorId().toString(),
                        c.shipmentNo());
        String override = existing.isEmpty()
                ? null : (String) existing.getFirst().get("logistics_overrides");
        LogisticsEdit logistics = override == null ? null : json.readValue(override, LogisticsEdit.class);
        var quantity =
                SalesShipmentSnapshotFacts.lines(json.writeValueAsString(c.payload())).stream()
                        .map(l -> l.shippedQuantity())
                        .filter(Objects::nonNull)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        jdbc.update(
                "UPDATE order_sales_shipment SET"
                    + " sales_order_no_snapshot=?,customer_code_snapshot=?,customer_name_snapshot=?,contact_phone_snapshot=?,source_warehouse_name=?,shipment_status_code=?,logistics_company=?,tracking_no=?,ship_time=CASE WHEN ? THEN ship_time ELSE ? END,stock_out_time=?,source_updated_at=?,source_payload_json=?,total_quantity=?,updated_time=?,updated_by='SYSTEM',revision=revision+1"
                    + " WHERE tenant_id=? AND id=? AND deleted=0",
                c.sourceOrderNo(),
                c.customerCode(),
                c.customerName(),
                c.contactPhone(),
                c.warehouseName(),
                c.statusCode(),
                logistics == null || logistics.carrierName() == null
                        ? c.logisticsCompany() : logistics.carrierName(),
                logistics == null || logistics.trackingNumbers() == null ? c.trackingNo()
                        : logistics.trackingNumbers().isEmpty() ? null : String.join(",", logistics.trackingNumbers()),
                logistics != null && logistics.shipTime() != null,
                ts(c.shipTime()),
                ts(c.stockOutTime()),
                ts(c.sourceUpdatedAt()),
                json.writeValueAsString(c.payload()),
                quantity,
                now,
                tenant,
                id);
        cache.changed(tenant);
        return id;
    }

    private static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }
}
