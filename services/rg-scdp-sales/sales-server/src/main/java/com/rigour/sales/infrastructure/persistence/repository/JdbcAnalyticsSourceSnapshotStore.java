package com.rigour.sales.infrastructure.persistence.repository;

import com.rigour.sales.application.port.out.AnalyticsSourceSnapshotStore;

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
            Map.of(
                    "SALES_SUBMITTED_VISIT",
                    new Definition(
                            """
(SELECT s.id,s.tenant_id,s.store_id,NULLIF(TRIM(st.city),'') city_name,s.submitted_at,s.review_status,s.salesperson_id,
 e.employee_code owner_staff_code,c.customer_id,c.customer_code
 FROM temp_sales_checkin_submission s JOIN temp_sales_checkin_store st ON st.tenant_id=s.tenant_id AND st.id=s.store_id
 LEFT JOIN temp_sales_checkin_employee_link e ON e.tenant_id=s.tenant_id AND e.salesperson_id=s.salesperson_id
 LEFT JOIN temp_sales_checkin_customer_link c ON c.tenant_id=s.tenant_id AND c.store_id=s.store_id
 WHERE s.status='SUBMITTED' AND s.deletion_state='NONE' AND s.submitted_at IS NOT NULL) source_rows
""",
                            List.of(
                                    "id",
                                    "tenant_id",
                                    "store_id",
                                    "city_name",
                                    "submitted_at",
                                    "review_status",
                                    "salesperson_id",
                                    "owner_staff_code",
                                    "customer_id",
                                    "customer_code"),
                            true,
                            true,
                            "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(CAST(CRC32(CONCAT_WS('|',COALESCE(HEX(id),''),COALESCE(HEX(tenant_id),''),COALESCE(HEX(store_id),''),COALESCE(HEX(city_name),''),COALESCE(HEX(submitted_at),''),COALESCE(HEX(review_status),''),COALESCE(HEX(salesperson_id),''),COALESCE(HEX(owner_staff_code),''),COALESCE(HEX(customer_id),''),COALESCE(HEX(customer_code),'')))"
                                + " AS UNSIGNED)),0))"));

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
