package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.application.port.out.AnalyticsSourceSnapshotStore;

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
                            "CRM_CONTACT",
                            new Definition(
                                    "crm_contact",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "party_id",
                                            "contact_name",
                                            "phone",
                                            "is_primary",
                                            "status",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    true,
                                    true,
                                    "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "CRM_CUSTOMER",
                            new Definition(
                                    "crm_customer",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "customer_code",
                                            "party_id",
                                            "customer_name",
                                            "customer_source_name",
                                            "business_category_name",
                                            "contact_name",
                                            "contact_phone",
                                            "customer_type_code",
                                            "region_code",
                                            "region_name",
                                            "owner_sales_name",
                                            "owner_employee_code",
                                            "owner_employee_name_snapshot",
                                            "status_code",
                                            "source_system_code",
                                            "source_document_no",
                                            "business_created_at",
                                            "business_created_by_id",
                                            "business_created_by_name",
                                            "business_creation_source",
                                            "source_created_at",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    false,
                                    false,
                                    "CONCAT(COUNT(*),':',COALESCE(CAST(MIN(id) AS"
                                        + " CHAR),''),':',COALESCE(CAST(MAX(id) AS"
                                        + " CHAR),''),':',COALESCE(SUM(UNIX_TIMESTAMP(source_created_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(source_updated_at)*1000000),0),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "CRM_CUSTOMER_AREA",
                            new Definition(
                                    "crm_customer_area",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "area_code",
                                            "area_name",
                                            "parent_area_code",
                                            "status",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    true,
                                    true,
                                    "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "CRM_CUSTOMER_POLICY",
                            new Definition(
                                    "crm_customer_policy",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "party_id",
                                            "payment_term_days",
                                            "status",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    true,
                                    true,
                                    "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")),
                    Map.entry(
                            "CRM_CUSTOMER_TYPE",
                            new Definition(
                                    "crm_customer_type",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "type_code",
                                            "type_name",
                                            "status",
                                            "created_time",
                                            "updated_time",
                                            "deleted"),
                                    true,
                                    true,
                                    "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_time)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))")));

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
