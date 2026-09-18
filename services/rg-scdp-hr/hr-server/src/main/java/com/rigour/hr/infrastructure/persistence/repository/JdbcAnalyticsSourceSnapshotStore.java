package com.rigour.hr.infrastructure.persistence.repository;

import com.rigour.hr.application.port.out.AnalyticsSourceSnapshotStore;

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
                    "HR_EMPLOYEE",
                    new Definition(
                            """
(SELECT e.id,e.tenant_id,e.employee_code,e.employee_name,e.employment_status,e.city_name,
 COALESCE(NULLIF(p.position_name,''),NULLIF(e.primary_position_name_snapshot,''),NULLIF(e.job_category,'')) position_name,
 COALESCE(d.department_name,e.department_name_snapshot) department_name,e.department_id,
 COALESCE((SELECT JSON_ARRAYAGG(c.ancestor_id) FROM hr_department_closure c WHERE c.tenant_id=e.tenant_id AND c.descendant_id=e.department_id),JSON_ARRAY()) department_path,
 e.entry_date,e.leave_date,e.revision,e.access_version,e.updated_time
 FROM hr_employee e LEFT JOIN hr_position p ON p.tenant_id=e.tenant_id AND p.position_code=e.primary_position_code AND p.deleted=0
 LEFT JOIN hr_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id AND d.deleted=0
 WHERE e.deleted=0) source_rows
""",
                            List.of(
                                    "id",
                                    "tenant_id",
                                    "employee_code",
                                    "employee_name",
                                    "employment_status",
                                    "city_name",
                                    "position_name",
                                    "department_name",
                                    "department_id",
                                    "department_path",
                                    "entry_date",
                                    "leave_date"),
                            false,
                            false,
                            "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(CAST(CRC32(CONCAT_WS('|',COALESCE(HEX(id),''),COALESCE(HEX(tenant_id),''),COALESCE(HEX(employee_code),''),COALESCE(HEX(employee_name),''),COALESCE(HEX(employment_status),''),COALESCE(HEX(city_name),''),COALESCE(HEX(position_name),''),COALESCE(HEX(department_name),''),COALESCE(HEX(department_id),''),COALESCE(HEX(department_path),''),COALESCE(HEX(entry_date),''),COALESCE(HEX(leave_date),'')))"
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
