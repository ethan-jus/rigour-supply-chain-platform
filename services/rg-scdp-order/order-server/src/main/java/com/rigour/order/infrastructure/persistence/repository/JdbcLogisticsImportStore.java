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
                            + " carrier_code='HLL' AND"
                            + " JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.ledger.platformOrderNo'))=?",
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
        boolean partial = "LIST".equals(order.raw().get("详情状态"));
        var last = current.latestOrder() == null ? current.order() : current.latestOrder();
        if (partial && "DETAIL".equals(last.raw().get("详情状态"))) {
            order =
                    new CarrierOrder(
                            last.enterpriseId(),
                            last.orderNo(),
                            order.status(),
                            order.useTime(),
                            order.paidAmount() == null ? last.paidAmount() : order.paidAmount(),
                            last.raw(),
                            last.salesOrderNos(),
                            last.line(),
                            last.facts());
            encoded = json.writeValueAsString(order);
        }
        if (!partial && last.facts() != null && order.facts() != null) {
            var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(order);
            var prior = json.valueToTree(last);
            for (var field :
                    Map.of(
                                    "fees",
                                    "费用明细已提供",
                                    "receipts",
                                    "回单已提供",
                                    "billVouchers",
                                    "账单凭证已提供",
                                    "stops",
                                    "路线已提供")
                            .entrySet())
                if ("false".equals(order.raw().get(field.getValue())))
                    ((tools.jackson.databind.node.ObjectNode) tree.path("facts"))
                            .set(field.getKey(), prior.path("facts").path(field.getKey()));
            for (String field :
                    List.of(
                            "orderAmount",
                            "billAmount",
                            "refundAmount",
                            "couponAmount",
                            "bonusAmount",
                            "unpaidAmount"))
                if (tree.path("facts").path(field).isNull())
                    ((tools.jackson.databind.node.ObjectNode) tree.path("facts"))
                            .set(field, prior.path("facts").path(field));
            if (tree.path("paidAmount").isNull()) tree.set("paidAmount", prior.path("paidAmount"));
            if (sameEmployee(order, last))
                for (String field :
                        List.of("sourceEmployeeName", "sourceStaffId", "sourceDepartmentName"))
                    if (tree.path("facts").path(field).asText("").isBlank())
                        ((tools.jackson.databind.node.ObjectNode) tree.path("facts"))
                                .set(field, prior.path("facts").path(field));
            if ("false".equals(order.raw().get("路线已提供")))
                ((tools.jackson.databind.node.ObjectNode) tree.path("raw"))
                        .set("收货人信息", prior.path("raw").path("收货人信息"));
            for (String field : List.of("司机", "司机联系电话", "车牌号", "下单员工手机号"))
                if (tree.path("raw").path(field).asText("").isBlank()
                        && !prior.path("raw").path(field).asText("").isBlank())
                    ((tools.jackson.databind.node.ObjectNode) tree.path("raw"))
                            .put(field, prior.path("raw").path(field).asText());
            order = json.treeToValue(tree, CarrierOrder.class);
            encoded = json.writeValueAsString(order);
        }
        // 旧批次的目录快照不能撤回已经投递的新员工关联。
        if (sameEmployee(order, last)
                && last.facts().employeeMappingRevision()
                        > order.facts().employeeMappingRevision()) {
            order =
                    mapped(
                            order,
                            last.facts().mappedEmployeeCode(),
                            last.facts().employeeMappingRevision());
            encoded = json.writeValueAsString(order);
        }
        // 凭证由独立下载队列补齐；后续账单同步不能把已保存的自有COS引用退回待获取。
        if (!partial && last.facts() != null && order.facts() != null) {
            var known = new HashMap<String, Voucher>();
            for (var fee : last.facts().fees())
                for (var v : fee.vouchers() == null ? List.<Voucher>of() : fee.vouchers())
                    if ("READY".equals(v.state()) || "PENDING".equals(v.state()))
                        known.put(v.id(), v);
            for (var list : Arrays.asList(last.facts().receipts(), last.facts().billVouchers()))
                if (list != null)
                    for (var v : list)
                        if ("READY".equals(v.state()) || "PENDING".equals(v.state()))
                            known.put(v.id(), v);
            if (!known.isEmpty()) {
                var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(order);
                for (var fee : tree.path("facts").path("fees"))
                    for (var v : fee.path("vouchers"))
                        if (known.containsKey(v.path("id").asText())
                                && "READY".equals(known.get(v.path("id").asText()).state()))
                            ((tools.jackson.databind.node.ObjectNode) v)
                                    .setAll(
                                            (tools.jackson.databind.node.ObjectNode)
                                                    json.valueToTree(
                                                            known.get(v.path("id").asText())));
                for (String field : List.of("receipts", "billVouchers"))
                    for (var v : tree.path("facts").path(field))
                        if (known.containsKey(v.path("id").asText())
                                && "READY".equals(known.get(v.path("id").asText()).state()))
                            ((tools.jackson.databind.node.ObjectNode) v)
                                    .setAll(
                                            (tools.jackson.databind.node.ObjectNode)
                                                    json.valueToTree(
                                                            known.get(v.path("id").asText())));
                for (var next : tree.path("facts").path("fees"))
                    for (var oldFee : json.valueToTree(last).path("facts").path("fees")) {
                        if (!next.path("lineId").asText().equals(oldFee.path("lineId").asText())
                                || !next.path("name").asText().equals(oldFee.path("name").asText())
                                || !sameMoney(
                                        next.path("amount").isNumber()
                                                ? next.path("amount").decimalValue()
                                                : null,
                                        oldFee.path("amount").isNumber()
                                                ? oldFee.path("amount").decimalValue()
                                                : null)) continue;
                        var node = (tools.jackson.databind.node.ObjectNode) next;
                        var list = node.withArray("vouchers");
                        var ids = new HashSet<String>();
                        for (var v : list) ids.add(v.path("id").asText());
                        for (var v : oldFee.path("vouchers"))
                            if (Set.of("READY", "PENDING").contains(v.path("state").asText())
                                    && ids.add(v.path("id").asText())) list.add(v);
                    }
                order = json.treeToValue(tree, CarrierOrder.class);
                encoded = json.writeValueAsString(order);
            }
        }
        String incoming =
                (partial && "DETAIL".equals(last.raw().get("详情状态"))) || sameBill(order, last)
                        ? previous
                        : source.payloadHash();
        boolean difference = current.costId() != null && !Objects.equals(incoming, accepted);
        boolean changed = !Objects.equals(incoming, previous);
        if (changed && current.costId() != null && last.facts() != null) {
            var history =
                    jdbc.queryForObject(
                            "SELECT bill_history_json FROM order_logistics_import_order WHERE"
                                    + " tenant_id=? AND id=?",
                            String.class,
                            tenant,
                            current.id());
            var values = new ArrayList<CarrierOrder>();
            values.add(last);
            if (history != null)
                values.addAll(Arrays.asList(json.readValue(history, CarrierOrder[].class)));
            jdbc.update(
                    "UPDATE order_logistics_import_order SET bill_history_json=? WHERE tenant_id=?"
                            + " AND id=?",
                    json.writeValueAsString(values),
                    tenant,
                    current.id());
        }
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
                incoming,
                difference,
                encoded,
                incoming,
                changed ? 1 : 0,
                changed,
                tenant,
                current.id());
        return lockManaged(tenant, actor, true, current.id())
                .withSyncOutcome(inserted ? "CREATED" : changed ? "UPDATED" : "UNCHANGED");
    }

    @Override
    public void employeeMapping(String tenant, EmployeeMappingUpdate update) {
        // 只改来源中的当前人员关联，不改账单历史、成本金额、单据关联及归属快照。
        var rows =
                jdbc.query(
                        "SELECT * FROM order_logistics_import_order WHERE tenant_id=? AND"
                                + " enterprise_id=? FOR UPDATE",
                        mapper(),
                        tenant,
                        update.enterpriseId());
        for (var row : rows) {
            var source = mapIfNewer(row.order(), update);
            var latest = row.latestOrder() == null ? null : mapIfNewer(row.latestOrder(), update);
            if (source == row.order() && latest == row.latestOrder()) continue;
            jdbc.update(
                    "UPDATE order_logistics_import_order SET source_json=?,latest_source_json=?"
                            + " WHERE tenant_id=? AND id=?",
                    json.writeValueAsString(source),
                    latest == null ? null : json.writeValueAsString(latest),
                    tenant,
                    row.id());
        }
    }

    private CarrierOrder mapIfNewer(CarrierOrder order, EmployeeMappingUpdate update) {
        var f = order.facts();
        if (f == null
                || !Objects.equals(f.connectorId(), update.connectorId())
                || !Objects.equals(f.sourceUserId(), update.userId())
                || f.employeeMappingRevision() >= update.revision()) return order;
        return mapped(order, update.employeeCode(), update.revision());
    }

    private CarrierOrder mapped(CarrierOrder order, String employeeCode, long revision) {
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(order);
        var facts = (tools.jackson.databind.node.ObjectNode) node.path("facts");
        facts.put("mappedEmployeeCode", employeeCode);
        facts.put("employeeMappingRevision", revision);
        return json.treeToValue(node, CarrierOrder.class);
    }

    private static boolean sameEmployee(CarrierOrder a, CarrierOrder b) {
        return a.facts() != null
                && b.facts() != null
                && Objects.equals(a.facts().connectorId(), b.facts().connectorId())
                && a.facts().sourceUserId() != null
                && !a.facts().sourceUserId().isBlank()
                && Objects.equals(a.facts().sourceUserId(), b.facts().sourceUserId());
    }

    /** 比较合并后的金额事实，避免缺项回执、数值精度及凭证更新制造虚假账单差异。 */
    private static boolean sameBill(CarrierOrder left, CarrierOrder right) {
        var a = left.facts();
        var b = right.facts();
        if (a == null || b == null) return false;
        return Objects.equals(left.enterpriseId(), right.enterpriseId())
                && Objects.equals(left.orderNo(), right.orderNo())
                && sameMoney(a.billAmount(), b.billAmount())
                && sameMoney(a.refundAmount(), b.refundAmount())
                && sameMoney(a.couponAmount(), b.couponAmount())
                && sameMoney(a.bonusAmount(), b.bonusAmount())
                && billFees(a.fees()).equals(billFees(b.fees()));
    }

    private static boolean sameMoney(java.math.BigDecimal a, java.math.BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static Map<List<String>, Long> billFees(List<Fee> fees) {
        var result = new HashMap<List<String>, Long>();
        for (var f : fees == null ? List.<Fee>of() : fees) {
            var key =
                    Arrays.asList(
                            f.code(),
                            f.name(),
                            f.amount() == null
                                    ? null
                                    : f.amount().stripTrailingZeros().toPlainString());
            result.merge(key, 1L, Long::sum);
        }
        return result;
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
    public List<Row> byCosts(String tenant, List<String> costIds) {
        if (costIds.isEmpty()) return List.of();
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(costIds);
        return jdbc.query(
                "SELECT * FROM order_logistics_import_order WHERE tenant_id=? AND cost_id IN ("
                        + String.join(",", Collections.nCopies(costIds.size(), "?"))
                        + ")",
                mapper(),
                args.toArray());
    }

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
