package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.AnalyticsSourceSnapshotStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 本领域固定投影目录；所有动态标识来自代码常量，外部输入仅作为绑定值。 */
@Repository
public class JdbcAnalyticsSourceSnapshotStore implements AnalyticsSourceSnapshotStore {
    private final JdbcTemplate jdbc;

    public JdbcAnalyticsSourceSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Definition(
            String table,
            List<String> columns,
            boolean binaryTenant,
            boolean binaryKey,
            String versionExpression) {}

    private static final Map<String, Definition> DATASETS =
            Map.ofEntries(
                    Map.entry(
                            "ORDER_PAYMENT_RECORD",
                            new Definition(
                                    "order_payment_record",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "payment_no",
                                            "source_system_code",
                                            "source_document_no",
                                            "order_id",
                                            "sales_order_no_snapshot",
                                            "customer_id",
                                            "customer_code_snapshot",
                                            "customer_name_snapshot",
                                            "collector_staff_code",
                                            "collector_name_snapshot",
                                            "payment_time",
                                            "payment_method_code",
                                            "paid_amount",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(UNIX_TIMESTAMP(payment_time)*1000000),0),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ORDER_REFUND_RECORD",
                            new Definition(
                                    "order_refund_record",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "order_id",
                                            "customer_id",
                                            "customer_code_snapshot",
                                            "customer_name_snapshot",
                                            "refund_status_code",
                                            "refund_amount",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(UNIX_TIMESTAMP(refund_time)*1000000),0),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ORDER_SALES_ORDER",
                            new Definition(
                                    "order_sales_order",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "order_no",
                                            "source_system_code",
                                            "source_order_no",
                                            "customer_id",
                                            "customer_code_snapshot",
                                            "customer_name_snapshot",
                                            "region_code",
                                            "owner_sales_name",
                                            "owner_employee_code",
                                            "owner_employee_name_snapshot",
                                            "order_date",
                                            "order_status_code",
                                            "order_type_code",
                                            "payment_method_code",
                                            "payment_status_code",
                                            "outbound_status_code",
                                            "total_quantity",
                                            "discount_rate",
                                            "discount_amount",
                                            "payable_amount",
                                            "paid_amount",
                                            "unpaid_amount",
                                            "created_time",
                                            "updated_time",
                                            "deleted",
                                            "payment_time",
                                            "shipment_time"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(UNIX_TIMESTAMP(order_date)*1000000),0),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(payment_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(shipment_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(warehouse_selected_at)*1000000),0))")),
                    Map.entry(
                            "ORDER_SALES_ORDER_LINE",
                            new Definition(
                                    "order_sales_order_line",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "order_id",
                                            "product_id",
                                            "product_variant_id",
                                            "product_code_snapshot",
                                            "sku_code_snapshot",
                                            "product_name_snapshot",
                                            "specification_snapshot",
                                            "unit_code",
                                            "quantity",
                                            "unit_price",
                                            "discount_rate",
                                            "discount_amount",
                                            "line_amount",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")));

    private Definition definition(String code) {
        var d = DATASETS.get(code);
        if (d == null) throw new IllegalArgumentException("未知 BI 源数据集");
        return d;
    }

    private String tenantPredicate(Definition d) {
        return "tenant_id=" + (d.binaryTenant() ? "UUID_TO_BIN(?)" : "?");
    }

    @Override
    public String version(String tenant, String dataset) {
        var d = definition(dataset);
        return jdbc.queryForObject(
                "SELECT "
                        + d.versionExpression()
                        + " FROM "
                        + d.table()
                        + " WHERE "
                        + tenantPredicate(d),
                String.class,
                tenant);
    }

    @Override
    @Transactional(readOnly = true)
    public Page page(String tenant, String dataset, String after) {
        var d = definition(dataset);
        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !cursor.matches(d.binaryKey() ? "[0-9a-fA-F]{32}" : "[0-9]{1,20}"))
            throw new IllegalArgumentException("源数据游标无效");
        String version = version(tenant, dataset);
        var args = new ArrayList<Object>();
        args.add(tenant);
        String where = tenantPredicate(d);
        if (!cursor.isEmpty()) {
            where += " AND id>" + (d.binaryKey() ? "UNHEX(?)" : "?");
            args.add(cursor);
        }
        var rows =
                jdbc.queryForList(
                        "SELECT "
                                + String.join(",", d.columns())
                                + " FROM "
                                + d.table()
                                + " WHERE "
                                + where
                                + " ORDER BY id LIMIT 1000",
                        args.toArray());
        List<Map<String, String>> items = new ArrayList<>();
        for (var row : rows) {
            Map<String, String> item = new LinkedHashMap<>();
            for (String column : d.columns()) item.put(column, exact(row.get(column)));
            items.add(item);
        }
        return new Page(version, items);
    }

    private static String exact(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] bytes) return HexFormat.of().withUpperCase().formatHex(bytes);
        if (value instanceof java.math.BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof java.sql.Timestamp timestamp)
            return timestamp.toLocalDateTime().toString().replace('T', ' ');
        if (value instanceof java.time.LocalDateTime time) return time.toString().replace('T', ' ');
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        return value.toString();
    }
}
