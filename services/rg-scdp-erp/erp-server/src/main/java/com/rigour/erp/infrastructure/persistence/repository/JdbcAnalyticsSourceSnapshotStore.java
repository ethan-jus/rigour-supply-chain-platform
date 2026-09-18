package com.rigour.erp.infrastructure.persistence.repository;

import com.rigour.erp.application.port.out.AnalyticsSourceSnapshotStore;

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
                            "ERP_INVENTORY_WAREHOUSE",
                            new Definition(
                                    "erp_inventory_warehouse",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "warehouse_code",
                                            "warehouse_name",
                                            "region_code",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_PROCUREMENT_ORDER",
                            new Definition(
                                    "erp_procurement_order",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "procurement_no",
                                            "source_document_no",
                                            "target_warehouse_id",
                                            "status_code",
                                            "expected_arrival_time",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(UNIX_TIMESTAMP(expected_arrival_time)*1000000),0),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_PROCUREMENT_ORDER_LINE",
                            new Definition(
                                    "erp_procurement_order_line",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "procurement_order_id",
                                            "product_id",
                                            "product_variant_id",
                                            "product_code_snapshot",
                                            "variant_code_snapshot",
                                            "product_name_snapshot",
                                            "unit_code",
                                            "quantity",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_PRODUCT",
                            new Definition(
                                    "erp_product",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "product_code",
                                            "product_name",
                                            "category_id",
                                            "brand_id",
                                            "unit_code",
                                            "shelf_status_code",
                                            "submit_status_code",
                                            "source_system_code",
                                            "source_document_no",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(UNIX_TIMESTAMP(source_created_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(source_updated_at)*1000000),0),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_PRODUCT_BRAND",
                            new Definition(
                                    "erp_product_brand",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "brand_code",
                                            "brand_name",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_PRODUCT_CATEGORY",
                            new Definition(
                                    "erp_product_category",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "parent_id",
                                            "category_code",
                                            "category_name",
                                            "category_level",
                                            "ordinal",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_PRODUCT_VARIANT",
                            new Definition(
                                    "erp_product_variant",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "product_id",
                                            "variant_code",
                                            "specification_snapshot",
                                            "unit_code",
                                            "purchase_price",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_STOCK_BALANCE",
                            new Definition(
                                    "erp_stock_balance",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "warehouse_id",
                                            "product_id",
                                            "product_variant_id",
                                            "available_quantity",
                                            "locked_quantity",
                                            "in_transit_quantity",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_STOCK_OUT_ORDER",
                            new Definition(
                                    "erp_stock_out_order",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "stock_out_no",
                                            "source_document_no",
                                            "stock_out_type_code",
                                            "warehouse_id",
                                            "status_code",
                                            "stock_out_time",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(UNIX_TIMESTAMP(stock_out_time)*1000000),0),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ERP_STOCK_OUT_ORDER_LINE",
                            new Definition(
                                    "erp_stock_out_order_line",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "stock_out_order_id",
                                            "product_id",
                                            "product_variant_id",
                                            "product_code_snapshot",
                                            "variant_code_snapshot",
                                            "product_name_snapshot",
                                            "unit_code",
                                            "quantity",
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
