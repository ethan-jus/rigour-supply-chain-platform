package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.PaymentVoucherModels.TransactionMatch;
import com.rigour.order.api.v1.model.PaymentVoucherModels.VoucherTransaction;
import com.rigour.order.application.port.out.PaymentVoucherStore;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentVoucherStore implements PaymentVoucherStore {
    private final JdbcTemplate jdbc;
    private final OrderDataScope scopes;
    public JdbcPaymentVoucherStore(JdbcTemplate jdbc, OrderDataScope scopes) { this.jdbc = jdbc; this.scopes = scopes; }
    @Override public List<String> attachmentKeys(String tenantId, long paymentId) {
        var scope = scopes.predicate("order:read", "o.", null);
        var args = new ArrayList<Object>(List.of(tenantId, paymentId)); args.addAll(scope.args());
        return jdbc.query("SELECT p.voucher_keys_json FROM order_payment_record p JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id WHERE p.tenant_id=? AND p.id=? AND p.deleted=0 AND (" + scope.sql() + ")",
            rs -> {
                if (!rs.next() || rs.getString(1) == null || rs.getString(1).isBlank()) return List.of();
                return java.util.Arrays.asList(tools.jackson.databind.json.JsonMapper.builder().build().readValue(rs.getString(1), String[].class));
            }, args.toArray());
    }
    @Override public List<VoucherTransaction> vouchers(String tenantId, long paymentId) {
        var scope = scopes.predicate("order:read", "o.", null);
        var args = new ArrayList<Object>(List.of(tenantId, tenantId, paymentId)); args.addAll(scope.args());
        return jdbc.query("""
                SELECT e.voucher_key,e.voucher_amount,e.transaction_no,e.evidence_note
                FROM order_payment_voucher_transaction e
                JOIN order_payment_record p ON p.tenant_id=? AND p.id=e.payment_id
                JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
                WHERE e.tenant_id=? AND e.payment_id=? AND p.deleted=0
                """ + " AND (" + scope.sql() + ") ORDER BY e.voucher_key", (rs, n) -> new VoucherTransaction(rs.getString(1), rs.getBigDecimal(2),
                        rs.getString(3), rs.getString(4), null), args.toArray());
    }
    @Override public List<TransactionMatch> transactionMatches(String tenantId, String transactionNo) {
        // 同时查主单历史单号和逐图单号；历史删除记录也展示，避免误判“从未使用”。
        // 每张凭证保留关联行，主单与其凭证的同一个单号不重复计数。
        var scope = scopes.predicate("order:read", "o.", null);
        var args = new ArrayList<Object>(List.of(tenantId, transactionNo, tenantId, transactionNo)); args.addAll(scope.args());
        return jdbc.query("""
                SELECT p.id,p.payment_no,o.order_no,p.customer_name_snapshot,
                       o.owner_employee_name_snapshot,p.paid_amount,p.payment_time,p.payment_status_code,
                       (p.deleted<>0 OR COALESCE(o.deleted,0)<>0) AS deleted,
                       e.voucher_key,e.voucher_amount,e.evidence_note
                FROM order_payment_record p
                LEFT JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
                LEFT JOIN order_payment_voucher_transaction e ON e.tenant_id=?
                     AND e.payment_id=p.id AND e.transaction_no=?
                WHERE p.tenant_id=? AND (TRIM(p.transaction_no)=? OR e.transaction_no IS NOT NULL)
                """ + " AND (" + scope.sql() + ") ORDER BY p.payment_time DESC,p.id,e.voucher_key", (rs, n) -> {
                    LocalDateTime time = rs.getObject("payment_time", LocalDateTime.class);
                    return new TransactionMatch(rs.getString("id"), rs.getString("payment_no"),
                            rs.getString("order_no"), rs.getString("customer_name_snapshot"),
                            rs.getString("owner_employee_name_snapshot"), rs.getBigDecimal("paid_amount"),
                            time == null ? null : time.toInstant(ZoneOffset.UTC),
                            rs.getString("payment_status_code"), rs.getBoolean("deleted"),
                            rs.getString("voucher_key"), rs.getBigDecimal("voucher_amount"),
                            rs.getString("evidence_note"));
                }, args.toArray());
    }
}
