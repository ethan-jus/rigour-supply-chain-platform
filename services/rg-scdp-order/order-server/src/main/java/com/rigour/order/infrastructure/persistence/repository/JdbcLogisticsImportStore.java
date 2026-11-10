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
                        json.readValue(r.getString("source_json"), CarrierOrder.class),
                        r.getString("source_channel"),
                        r.getBoolean("source_difference"),
                        r.getString("latest_source_json") == null
                                ? null
                                : json.readValue(
                                        r.getString("latest_source_json"), CarrierOrder.class),
                        r.getTimestamp("last_synced_time") == null
                                ? null
                                : r.getTimestamp("last_synced_time").toInstant(),
                        null,
                        r.getString("bill_verification_json") == null
                                ? null
                                : json.readValue(
                                        r.getString("bill_verification_json"),
                                        BillVerification.class));
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
        var row = byCost(tenant, costId).orElseThrow();
        if (row.verification() != null) auditVerification(tenant, costId, row.verification());
    }

    @Override
    public List<Row> query(String tenant, String actor, boolean administrator, SourceQuery q) {
        String sql = "SELECT * FROM order_logistics_import_order WHERE tenant_id=?";
        var args = new ArrayList<Object>();
        args.add(tenant);
        if (!administrator) {
            sql += " AND created_by=?";
            args.add(actor);
        }
        if (q.orderNo() != null && !q.orderNo().isBlank()) {
            sql += " AND carrier_order_no LIKE ?";
            args.add("%" + q.orderNo().strip() + "%");
        }
        if (q.state() != null && !q.state().isBlank()) {
            sql += " AND state=?";
            args.add(q.state());
        }
        if (q.differencesOnly()) sql += " AND source_difference=1";
        sql += " ORDER BY created_time DESC,id DESC LIMIT 100 OFFSET ?";
        args.add(q.begin());
        return jdbc.query(sql, mapper(), args.toArray());
    }

    @Override
    public Row lockManaged(String tenant, String actor, boolean administrator, String id) {
        if (!administrator) return lock(tenant, actor, id);
        return jdbc
                .query(
                        "SELECT * FROM order_logistics_import_order WHERE tenant_id=? AND id=? FOR"
                            + " UPDATE",
                        mapper(),
                        tenant,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("来源订单不存在或无权限"));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Row receive(String tenant, String actor, ApiSource source) {
        var order = source.order();
        String encoded = json.writeValueAsString(order);
        boolean inserted = false;
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " order_logistics_import_order(id,tenant_id,enterprise_id,carrier_order_no,file_name,file_hash,source_json,state,created_by,revision,source_channel,source_hash,latest_source_hash,latest_source_json,last_synced_time)"
                        + " VALUES(?,?,?,?,?,?,?,'UNMATCHED',?,1,'API',?,?,?,CURRENT_TIMESTAMP(6))",
                    UUID.randomUUID().toString(),
                    tenant,
                    order.enterpriseId(),
                    order.orderNo(),
                    "货拉拉接口",
                    source.payloadHash(),
                    encoded,
                    actor,
                    source.payloadHash(),
                    source.payloadHash(),
                    encoded);
            inserted = true;
        } catch (DuplicateKeyException duplicate) {
            /* 同一单号在API、Excel、重新授权间共用唯一来源。 */
        }
        var current =
                jdbc.query(
                                "SELECT * FROM order_logistics_import_order WHERE tenant_id=? AND"
                                    + " carrier_order_no=? FOR UPDATE",
                                mapper(),
                                tenant,
                                order.orderNo())
                        .getFirst();
        if (current.order().enterpriseId() != null
                && !current.order().enterpriseId().isBlank()
                && !current.order().enterpriseId().equals(order.enterpriseId()))
            throw new IllegalArgumentException("货拉拉订单所属企业与已有记录不一致");
        String accepted =
                jdbc.queryForObject(
                        "SELECT source_hash FROM order_logistics_import_order WHERE tenant_id=? AND"
                            + " id=?",
                        String.class,
                        tenant,
                        current.id());
        String previous =
                jdbc.queryForObject(
                        "SELECT latest_source_hash FROM order_logistics_import_order WHERE"
                            + " tenant_id=? AND id=?",
                        String.class,
                        tenant,
                        current.id());
        boolean difference = current.costId() != null && !source.payloadHash().equals(accepted);
        boolean changed = !source.payloadHash().equals(previous);
        jdbc.update(
                "UPDATE order_logistics_import_order SET"
                    + " enterprise_id=?,source_channel='API',latest_source_json=?,latest_source_hash=?,source_difference=?,source_json=CASE"
                    + " WHEN cost_id IS NULL THEN ? ELSE source_json END,source_hash=CASE WHEN"
                    + " cost_id IS NULL THEN ? ELSE source_hash"
                    + " END,revision=revision+?,bill_verification_json=CASE WHEN ? THEN NULL ELSE"
                    + " bill_verification_json"
                    + " END,last_synced_time=CURRENT_TIMESTAMP(6),updated_time=CURRENT_TIMESTAMP"
                    + " WHERE tenant_id=? AND id=?",
                order.enterpriseId(),
                encoded,
                source.payloadHash(),
                difference,
                encoded,
                source.payloadHash(),
                changed ? 1 : 0,
                changed,
                tenant,
                current.id());
        return lockManaged(tenant, actor, true, current.id())
                .withSyncOutcome(inserted ? "CREATED" : changed ? "UPDATED" : "UNCHANGED");
    }

    @Override
    public void accept(String tenant, String id, int revision, String actor, String reason) {
        var row = lockManaged(tenant, actor, true, id);
        if (jdbc.update(
                        "UPDATE order_logistics_import_order SET"
                            + " source_json=latest_source_json,source_hash=latest_source_hash,source_difference=0,revision=revision+1,updated_time=CURRENT_TIMESTAMP"
                            + " WHERE tenant_id=? AND id=? AND revision=? AND latest_source_json IS"
                            + " NOT NULL",
                        tenant,
                        id,
                        revision)
                != 1) throw new IllegalStateException("来源订单已变化，请刷新");
        if (row.costId() != null)
            jdbc.update(
                    "INSERT INTO"
                        + " order_logistics_cost_audit(tenant_id,entry_id,action_code,actor_id,reason,snapshot_json)"
                        + " VALUES(?,?,'SOURCE_ACCEPT',?,?,?)",
                    tenant,
                    row.costId(),
                    actor,
                    reason,
                    json.writeValueAsString(
                            Map.of(
                                    "sourceOrderNo",
                                    row.order().orderNo(),
                                    "previous",
                                    row.order(),
                                    "accepted",
                                    row.latestOrder())));
    }

    @Override
    public void verifyBill(String tenant, String id, int revision, BillVerification verification) {
        var row = lockManaged(tenant, verification.actor(), true, id);
        if (jdbc.update(
                        "UPDATE order_logistics_import_order SET"
                            + " bill_verification_json=?,revision=revision+1,updated_time=CURRENT_TIMESTAMP"
                            + " WHERE tenant_id=? AND id=? AND revision=?",
                        json.writeValueAsString(verification),
                        tenant,
                        id,
                        revision)
                != 1) throw new IllegalStateException("来源已变化，请刷新后核对");
        if (row.costId() != null) auditVerification(tenant, row.costId(), verification);
    }

    private void auditVerification(String tenant, String costId, BillVerification verification) {
        jdbc.update(
                "INSERT INTO"
                    + " order_logistics_cost_audit(tenant_id,entry_id,action_code,actor_id,reason,snapshot_json)"
                    + " VALUES(?,?,'BILL_VERIFY',?,?,?)",
                tenant,
                costId,
                verification.actor(),
                verification.reason(),
                json.writeValueAsString(verification));
    }

    @Override
    public Optional<Row> byCost(String tenant, String costId) {
        return jdbc
                .query(
                        "SELECT * FROM order_logistics_import_order WHERE tenant_id=? AND"
                            + " cost_id=?",
                        mapper(),
                        tenant,
                        costId)
                .stream()
                .findFirst();
    }
}
