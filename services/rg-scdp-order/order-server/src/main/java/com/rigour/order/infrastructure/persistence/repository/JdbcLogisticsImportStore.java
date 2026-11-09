package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.application.port.out.LogisticsImportStore;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Repository
public class JdbcLogisticsImportStore implements LogisticsImportStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcLogisticsImportStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private RowMapper<Row> mapper() {
        return (r, n) ->
                new Row(
                        r.getString("id"),
                        r.getInt("revision"),
                        r.getString("state"),
                        r.getString("cost_id"),
                        r.getString("file_name"),
                        json.readValue(r.getString("source_json"), CarrierOrder.class));
    }

    public List<Row> stage(String tenant, String actor, Upload upload) {
        List<Row> result = new ArrayList<>();
        for (var row : upload.rows()) {
            // 唯一键防止重传。重复报表保留首次原始回执，不覆盖已确认或人工维护的费用。
            try {
                jdbc.update(
                        "INSERT INTO order_logistics_import_order"
                            + " (id,tenant_id,enterprise_id,carrier_order_no,file_name,file_hash,source_json,state,created_by,revision)"
                            + " VALUES (?,?,?,?,?,?,?,'UNMATCHED',?,1)",
                        UUID.randomUUID().toString(),
                        tenant,
                        Objects.toString(row.enterpriseId(), ""),
                        row.orderNo(),
                        upload.fileName(),
                        upload.fileHash(),
                        json.writeValueAsString(row),
                        actor);
            } catch (DuplicateKeyException duplicate) {
                /* 首次回执为准，重传不覆盖。 */
            }
            var found =
                    jdbc.query(
                            "SELECT * FROM order_logistics_import_order WHERE tenant_id=? AND"
                                    + " carrier_order_no=? AND created_by=?",
                            mapper(),
                            tenant,
                            row.orderNo(),
                            actor);
            if (!found.isEmpty()) result.add(found.getFirst());
        }
        return result;
    }

    public List<Row> rows(String tenant, String actor, int begin) {
        return jdbc.query(
                "SELECT * FROM order_logistics_import_order WHERE tenant_id=? AND created_by=?"
                        + " ORDER BY created_time DESC,id DESC LIMIT 100 OFFSET ?",
                mapper(),
                tenant,
                actor,
                begin);
    }

    public Row lock(String tenant, String actor, String id) {
        return jdbc
                .query(
                        "SELECT * FROM order_logistics_import_order WHERE tenant_id=? AND"
                                + " created_by=? AND id=? FOR UPDATE",
                        mapper(),
                        tenant,
                        actor,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("导入记录不存在或无权限"));
    }

    public boolean trackingExists(String tenant, String tracking) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_logistics_cost WHERE tenant_id=? AND"
                                + " carrier_code='HLL' AND tracking_no=?",
                        Long.class,
                        tenant,
                        tracking)
                > 0;
    }

    public void generated(String tenant, String id, int revision, String costId) {
        if (jdbc.update(
                        "UPDATE order_logistics_import_order SET"
                            + " cost_id=?,state='GENERATED',revision=revision+1,updated_time=CURRENT_TIMESTAMP"
                            + " WHERE tenant_id=? AND id=? AND revision=? AND cost_id IS NULL",
                        costId,
                        tenant,
                        id,
                        revision)
                != 1) throw new IllegalStateException("导入记录已变化，请刷新后重试");
    }
}
