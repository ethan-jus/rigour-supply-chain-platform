package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.HistorySyncModels.Intake;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** 仅拦截来源自动写入；保留来源证据，人工财务操作仍走原权限和审计。 */
final class ManualOrderSyncGuard {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    ManualOrderSyncGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Intake check(String tenant, UUID connector, String sourceNo, String receiptNo,
                 String paymentNo, String type, Object command) {
        var protectedOrders = jdbc.queryForList("SELECT source_no,order_id,reason,operation_id"
                + " FROM order_manual_sync_protection WHERE tenant_id=? AND connector_id=?",
                tenant, connector.toString());
        if (protectedOrders.isEmpty()) return null;
        var sources = new HashSet<String>();
        var orders = new HashSet<Long>();
        if (sourceNo != null) sources.add(sourceNo);
        if (receiptNo != null) {
            // 同时检查原关联，防止上游改绑或删除后重放绕过保护。
            sources.addAll(jdbc.queryForList("SELECT source_order_no FROM order_sync_receipt"
                    + " WHERE tenant_id=? AND connector_id=? AND receipt_no=?", String.class,
                    tenant, connector.toString(), receiptNo));
            orders.addAll(jdbc.queryForList("SELECT p.order_id FROM order_payment_record p WHERE p.tenant_id=?"
                    + " AND ((p.connector_id=? AND (p.source_record_id=? OR p.source_document_no=?))"
                    + " OR EXISTS(SELECT 1 FROM order_sync_allocation a WHERE a.tenant_id=?"
                    + " AND a.connector_id=? AND a.receipt_no=? AND a.payment_id=p.id))", Long.class,
                    tenant, connector.toString(), receiptNo, receiptNo, tenant, connector.toString(), receiptNo));
        }
        if (paymentNo != null) {
            orders.addAll(jdbc.queryForList("SELECT p.order_id FROM order_payment_record p"
                    + " WHERE p.tenant_id=? AND EXISTS(SELECT 1 FROM order_payment_voucher_source v"
                    + " WHERE v.tenant_id=? AND v.connector_id=? AND v.source_payment_no=? AND v.payment_id=p.id)",
                    Long.class, tenant, tenant, connector.toString(), paymentNo));
        }
        for (String source : sources) {
            if (source == null) continue;
            orders.addAll(jdbc.queryForList("SELECT m.order_id FROM order_history_member m"
                    + " WHERE m.tenant_id=? AND EXISTS(SELECT 1 FROM order_sync_source s"
                    + " WHERE s.tenant_id=? AND s.connector_id=? AND s.source_no=? AND s.group_id=m.group_id)",
                    Long.class, tenant, tenant, connector.toString(), source));
        }
        var matched = protectedOrders.stream().filter(p -> sources.contains(p.get("source_no"))
                || orders.contains(((Number) p.get("order_id")).longValue())).toList();
        if (matched.isEmpty()) return null;
        String sourceKey = paymentNo != null ? paymentNo : receiptNo != null ? receiptNo : sourceNo;
        String payload = JSON.writeValueAsString(command);
        String auditType = "PROTECTED_" + type;
        var latest = jdbc.queryForList("SELECT source_json FROM order_dhb_projection_change_audit"
                + " WHERE tenant_id=? AND connector_id=? AND object_type=? AND source_no=?"
                + " ORDER BY created_at DESC LIMIT 1", String.class,
                tenant, connector.toString(), auditType, sourceKey);
        if (latest.isEmpty() || !payload.equals(latest.getFirst())) {
            jdbc.update("INSERT INTO order_dhb_projection_change_audit"
                    + "(tenant_id,id,connector_id,object_type,source_no,before_json,source_json,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)", tenant, UUID.randomUUID().toString(),
                    connector.toString(), auditType, sourceKey, JSON.writeValueAsString(matched), payload,
                    java.sql.Timestamp.from(java.time.Instant.now()));
        }
        return new Intake("MANUAL_PROTECTED", null, "订单已人工修正并保护，来源变化已留痕，未覆盖订单、回款或凭证");
    }
}
