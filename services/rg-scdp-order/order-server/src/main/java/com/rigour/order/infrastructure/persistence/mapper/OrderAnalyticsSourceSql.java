package com.rigour.order.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;

/** 数据集白名单限制 SQL 标识符，租户与游标始终作为绑定参数。 */
public final class OrderAnalyticsSourceSql {
    private OrderAnalyticsSourceSql() {}

    private static final Map<String, OrderAnalyticsSourceDataset> DATASETS =
            Map.ofEntries(
                    Map.entry("ORDER_RECEIVABLE_OPENING", new OrderAnalyticsSourceDataset("order_receivable_opening_v",
                            List.of("id", "tenant_id", "baseline_at", "opening_paid"), false, false,
                            "CONCAT(COUNT(*),':',COALESCE(SUM(CRC32(JSON_ARRAY(id,tenant_id,baseline_at,opening_paid))),0),':',BIT_XOR(CRC32(JSON_ARRAY(opening_paid,baseline_at,tenant_id,id))))")),
                    Map.entry("ORDER_RECEIVABLE_ADJUSTMENT", new OrderAnalyticsSourceDataset("order_financial_event",
                            List.of("id", "tenant_id", "order_id", "payment_id", "event_type", "receivable_delta", "received_delta", "effective_at", "deleted"), false, false,
                            "CONCAT(COUNT(*),':',COALESCE(SUM(CRC32(JSON_ARRAY(id,tenant_id,order_id,payment_id,event_type,receivable_delta,received_delta,effective_at,deleted))),0),':',BIT_XOR(CRC32(JSON_ARRAY(deleted,effective_at,received_delta,receivable_delta,event_type,payment_id,order_id,tenant_id,id))))")),
                    Map.entry("ORDER_RECEIVABLE_PLAN", new OrderAnalyticsSourceDataset("order_receivable_installment",
                            List.of("id", "tenant_id", "order_id", "plan_version", "installment_no", "effective_at", "due_date", "amount"), false, false,
                            "CONCAT(COUNT(*),':',COALESCE(SUM(CRC32(JSON_ARRAY(id,tenant_id,order_id,plan_version,installment_no,effective_at,due_date,amount))),0),':',BIT_XOR(CRC32(JSON_ARRAY(amount,due_date,effective_at,installment_no,plan_version,order_id,tenant_id,id))))")),
                    Map.entry("ORDER_CASH_APPLICATION", new OrderAnalyticsSourceDataset("order_cash_application_v",
                            List.of("id", "tenant_id", "order_id", "cash_key", "effective_at", "amount", "verified"), false, false,
                            "CONCAT(COUNT(*),':',COALESCE(SUM(CRC32(JSON_ARRAY(id,tenant_id,order_id,cash_key,effective_at,amount,verified))),0),':',BIT_XOR(CRC32(JSON_ARRAY(verified,amount,effective_at,cash_key,order_id,tenant_id,id))))")),
                    Map.entry("ORDER_CASH_RECEIPT", new OrderAnalyticsSourceDataset("order_cash_receipt_v",
                            List.of("id", "tenant_id", "reference_no", "customer_id", "cash_at", "amount", "kind", "verified","channel","payee","owner_code","verified_at"), false, true,
                            "CONCAT(COUNT(*),':',COALESCE(SUM(CRC32(JSON_ARRAY(HEX(id),tenant_id,reference_no,customer_id,cash_at,amount,kind,verified,channel,payee,owner_code,verified_at))),0),':',BIT_XOR(CRC32(JSON_ARRAY(verified_at,owner_code,payee,channel,verified,kind,amount,cash_at,customer_id,reference_no,tenant_id,HEX(id)))))")),
                    Map.entry(
                            "ORDER_PAYMENT_STATUS",
                            new OrderAnalyticsSourceDataset(
                                    "order_payment_record",
                                    List.of("id", "tenant_id", "payment_status_code"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS CHAR),''),':',"
                                        + "COALESCE(SUM(revision),0),':',"
                                        + "COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "ORDER_PAYMENT_RECORD",
                            new OrderAnalyticsSourceDataset(
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
                                    "payment_owner_code",
                                    "payment_owner_name",
                                    "payment_owner_evidence",
                                    "payment_owner_department_id",
                                    "payment_owner_department_name",
                                    "payment_owner_region_code",
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
                            new OrderAnalyticsSourceDataset(
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
                            new OrderAnalyticsSourceDataset(
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
                            new OrderAnalyticsSourceDataset(
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

    public static OrderAnalyticsSourceDataset dataset(String code) {
        var dataset = DATASETS.get(code);
        if (dataset == null) throw new IllegalArgumentException("未知 BI 源数据集");
        return dataset;
    }

    public static String version(Map<String, Object> parameters) {
        var d = dataset((String) parameters.get("dataset"));
        return "SELECT "
                + d.versionExpression()
                + " FROM "
                + d.table()
                + " WHERE "
                + tenantPredicate(d);
    }

    public static String page(Map<String, Object> parameters) {
        var d = dataset((String) parameters.get("dataset"));
        String cursor = (String) parameters.get("after");
        String where = tenantPredicate(d);
        if (cursor != null && !cursor.isEmpty()) {
            if (!cursor.matches(d.binaryKey() ? "[0-9a-fA-F]{32}" : "[0-9]{1,20}"))
                throw new IllegalArgumentException("源数据游标无效");
            where += " AND id>" + (d.binaryKey() ? "UNHEX(#{after})" : "#{after}");
        }
        return "SELECT "
                + String.join(",", d.columns())
                + " FROM "
                + d.table()
                + " WHERE "
                + where
                + " ORDER BY id LIMIT 1000";
    }

    private static String tenantPredicate(OrderAnalyticsSourceDataset d) {
        return "tenant_id=" + (d.binaryTenant() ? "UUID_TO_BIN(#{tenant})" : "#{tenant}");
    }
}
