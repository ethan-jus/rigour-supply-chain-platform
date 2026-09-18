package com.rigour.integration.infrastructure.persistence.repository;

import com.rigour.integration.application.port.out.AnalyticsSourceSnapshotStore;

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
                            "INTEGRATION_FEISHU_IMPORT_BATCH",
                            new Definition(
                                    "integration_feishu_import_batch",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "source_url",
                                            "original_file_name",
                                            "file_sha256",
                                            "status",
                                            "total_rows",
                                            "created_at"),
                                    true,
                                    true,
                                    "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(UNIX_TIMESTAMP(created_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_at)*1000000),0),':',COALESCE(SUM(version),0))")),
                    Map.entry(
                            "INTEGRATION_FEISHU_IMPORT_RAW_ROW",
                            new Definition(
                                    "integration_feishu_import_raw_row",
                                    List.of(
                                            "id",
                                            "batch_id",
                                            "tenant_id",
                                            "sheet_name",
                                            "table_code",
                                            "source_row_number",
                                            "source_document_no",
                                            "source_created_at",
                                            "raw_row_hash",
                                            "row_json",
                                            "import_status",
                                            "projection_status",
                                            "error_code",
                                            "created_at",
                                            "updated_at"),
                                    true,
                                    true,
                                    "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(UNIX_TIMESTAMP(source_created_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(created_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_at)*1000000),0),':',COALESCE(SUM(version),0))")),
                    Map.entry(
                            "INTEGRATION_FEISHU_ONLINE_CAPTURE",
                            new Definition(
                                    "integration_feishu_online_capture",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "source_id",
                                            "source_name",
                                            "source_url",
                                            "started_at",
                                            "completed_at",
                                            "complete",
                                            "filtered",
                                            "checksum",
                                            "record_count",
                                            "page_count",
                                            "payload_json"),
                                    true,
                                    true,
                                    "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(UNIX_TIMESTAMP(started_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(completed_at)*1000000),0))")),
                    Map.entry(
                            "INTEGRATION_RAW_LANDING",
                            new Definition(
                                    "integration_raw_landing",
                                    List.of(
                                            "id",
                                            "tenant_id",
                                            "source_system",
                                            "source_object_type",
                                            "source_id",
                                            "received_at"),
                                    true,
                                    true,
                                    "CONCAT(COUNT(*),':',COALESCE(HEX(MIN(id)),''),':',COALESCE(HEX(MAX(id)),''),':',COALESCE(SUM(UNIX_TIMESTAMP(source_updated_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(received_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(processed_at)*1000000),0),':',COALESCE(SUM(UNIX_TIMESTAMP(last_attempt_at)*1000000),0),':',COALESCE(SUM(version),0))")));

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
