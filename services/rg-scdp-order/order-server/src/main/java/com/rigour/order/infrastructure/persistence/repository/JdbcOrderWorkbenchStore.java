package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.OrderWorkbenchApi.*;
import com.rigour.order.application.port.out.OrderWorkbenchStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOrderWorkbenchStore implements OrderWorkbenchStore {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rigour.shared.cache.QueryCache queryCache = com.rigour.shared.cache.QueryCache.disabled();
    private final JdbcTemplate jdbc;
    private final OrderDataScope scopes;
    public JdbcOrderWorkbenchStore(JdbcTemplate jdbc, OrderDataScope scopes) {
        this.jdbc = jdbc; this.scopes = scopes;
    }
    @Override public void requireSelf(String employee, String action) {
        var policy = scopes.policy(action);
        if (policy == null || !employee.equals(policy.employeeCode()))
            throw new com.rigour.shared.context.AuthorizationDeniedException("workbench-own-performance");
    }
    @Override @Transactional(readOnly = true)
    public Summary summary(String tenant, String employee, String action, Instant from, Instant until) {
        // Current authorization must be resolved even for a warm cache.
        var scope = scopes.predicate(action, "o.", null);
        return queryCache.read(tenant, "workbench-summary", List.of(employee, action, scope, from, until), 10,
                new tools.jackson.core.type.TypeReference<Summary>() {},
                () -> loadSummary(tenant, employee, scope, from, until));
    }
    private Summary loadSummary(String tenant, String employee, OrderDataScope.Predicate scope, Instant from, Instant until) {
        String where = " FROM order_sales_order o WHERE o.tenant_id=? AND o.owner_employee_code=? AND o.deleted=0"
                + " AND o.order_status_code IN ('SUBMITTED','COMPLETED') AND " + scope.sql();
        List<Object> scopeArgs = new ArrayList<>(List.of(tenant, employee));
        scopeArgs.addAll(scope.args());
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(until), Timestamp.from(from), Timestamp.from(until)));
        args.addAll(scopeArgs);
        var total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN o.order_date>=? AND o.order_date<? THEN o.payable_amount ELSE 0 END),0) trade,
                  COALESCE(SUM(CASE WHEN o.order_date>=? AND o.order_date<? THEN 1 ELSE 0 END),0) orders,
                  COALESCE(SUM(CASE WHEN o.unpaid_amount>0 THEN o.unpaid_amount ELSE 0 END),0) pending,
                  COALESCE(SUM(CASE WHEN o.unpaid_amount>0 THEN 1 ELSE 0 END),0) unpaid,
                  COALESCE(SUM(CASE WHEN o.outbound_status_code IN ('PENDING','PARTIAL_OUT') THEN 1 ELSE 0 END),0) outbound
                """ + where, (rs, n) -> new Summary(rs.getBigDecimal("trade"), rs.getLong("orders"),
                rs.getBigDecimal("pending"), rs.getLong("unpaid"), rs.getLong("outbound"), null), args.toArray());
        // Keep the existing preview contract; do not fetch discarded order/outbound detail pages.
        if (total.pendingCount() == 0) return total;
        var preview = jdbc.query("SELECT o.*" + where + " AND o.unpaid_amount>0 ORDER BY o.order_date DESC,o.id DESC LIMIT 1",
                (rs, n) -> new Row(rs.getString("id"), rs.getString("id"), rs.getString("order_no"),
                        rs.getString("customer_name_snapshot"), rs.getTimestamp("order_date") == null ? null : rs.getTimestamp("order_date").toInstant(),
                        rs.getBigDecimal("unpaid_amount"), rs.getString("payment_status_code")), scopeArgs.toArray());
        return new Summary(total.tradeAmount(), total.orderCount(), total.pendingAmount(), total.pendingCount(),
                total.outboundCount(), preview.isEmpty() ? null : preview.getFirst());
    }
    @Override @Transactional(readOnly = true)
    public Totals performanceTotals(String tenant, String employee, String action, Instant from, Instant until) {
        var scope = scopes.predicate(action, "o.", null);
        return queryCache.read(tenant, "workbench-performance", List.of(employee, action, scope, from, until), 10,
                new tools.jackson.core.type.TypeReference<Totals>() {}, () -> {
                    var page = performance(tenant, employee, scope, from, until, 0, 0);
                    return new Totals(page.total(), page.amount());
                });
    }
    @Override @Transactional(readOnly = true)
    public Page details(String tenant, String employee, String action, String kind,
                        Instant from, Instant until, int begin, int step) {
        var scope = scopes.predicate(action, "o.", null);
        if ("performance".equals(kind)) return performance(tenant, employee, scope, from, until, begin, step);
        boolean receipt = "receipts".equals(kind);
        String table = " FROM order_sales_order o" + (receipt
                ? " JOIN order_payment_record p ON p.tenant_id=o.tenant_id AND p.order_id=o.id" : "");
        String where = " WHERE o.tenant_id=? AND o.owner_employee_code=? AND o.deleted=0"
                + " AND o.order_status_code IN ('SUBMITTED','COMPLETED') AND " + scope.sql();
        List<Object> args = new ArrayList<>(List.of(tenant, employee));
        args.addAll(scope.args());
        if (receipt) where += " AND p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED')";
        String time = receipt ? "p.payment_time" : "o.order_date";
        if (receipt || "orders".equals(kind)) {
            where += " AND " + time + ">=? AND " + time + "<?";
            args.add(Timestamp.from(from)); args.add(Timestamp.from(until));
        } else if ("unpaid".equals(kind)) where += " AND o.unpaid_amount>0";
        else where += " AND o.outbound_status_code IN ('PENDING','PARTIAL_OUT')";
        String amount = receipt ? "p.paid_amount" : "unpaid".equals(kind) ? "o.unpaid_amount" : "o.payable_amount";
        var total = jdbc.queryForObject("SELECT COUNT(*) n,COALESCE(SUM(" + amount + "),0) amount" + table + where,
                (rs, n) -> new Page(rs.getLong("n"), rs.getBigDecimal("amount"), List.of()), args.toArray());
        String id = receipt ? "p.id" : "o.id";
        String number = receipt ? "p.payment_no" : "o.order_no";
        String status = receipt ? "p.payment_status_code" : "outbound".equals(kind) ? "o.outbound_status_code" : "o.payment_status_code";
        args.add(step); args.add(begin);
        var rows = jdbc.query("SELECT " + id + " id,o.id order_id," + number + " number,o.customer_name_snapshot,"
                + time + " occurred_at," + amount + " amount," + status + " status" + table + where
                + " ORDER BY " + time + " DESC," + id + " DESC LIMIT ? OFFSET ?", (rs, n) -> {
                    var at = rs.getTimestamp("occurred_at");
                    return new Row(rs.getString("id"), rs.getString("order_id"), rs.getString("number"),
                            rs.getString("customer_name_snapshot"), at == null ? null : at.toInstant(),
                            rs.getBigDecimal("amount"), rs.getString("status"));
                }, args.toArray());
        return new Page(total.total(), total.amount(), rows);
    }
    /** 与薪酬事实源一致：跨月累计已确认收款，首次整单付清时计一次整单金额。 */
    private Page performance(String tenant, String employee, OrderDataScope.Predicate scope, Instant from, Instant until, int begin, int step) {
        String cte = """
                WITH scoped AS (
                  SELECT o.*, CASE WHEN a.order_id IS NOT NULL THEN o.data_quality_status_code
                    WHEN NULLIF(o.source_system_code,'') IS NOT NULL AND NULLIF(o.owner_employee_code,'') IS NOT NULL
                      AND NULLIF(o.region_code,'') IS NOT NULL
                      AND NOT EXISTS(SELECT 1 FROM order_attribution_snapshot pending WHERE pending.tenant_id=o.tenant_id AND pending.order_id=o.id)
                    THEN o.data_quality_status_code ELSE 'ATTRIBUTION_PENDING' END quality
                  FROM order_sales_order o
                  LEFT JOIN order_attribution_snapshot a ON a.tenant_id=o.tenant_id AND a.order_id=o.id AND a.state='FROZEN'
                  WHERE o.tenant_id=? AND COALESCE(a.employee_code,o.owner_employee_code)=? AND o.order_date<?
                    AND o.deleted=0 AND o.order_status_code NOT IN ('DRAFT','CANCELLED','DELETED') AND
                """ + scope.sql() + """
                ), running AS (
                  SELECT p.order_id,p.payment_time,p.id,
                    SUM(p.paid_amount) OVER(PARTITION BY p.tenant_id,p.order_id ORDER BY p.payment_time,p.id ROWS UNBOUNDED PRECEDING) cumulative
                  FROM order_payment_record p JOIN scoped o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
                  WHERE p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time<?
                ), paid AS (
                  SELECT r.order_id,MIN(r.payment_time) fully_paid_time FROM running r JOIN scoped o ON o.id=r.order_id
                  WHERE o.payable_amount>0 AND r.cumulative-COALESCE((SELECT SUM(f.refund_amount) FROM order_refund_record f
                    WHERE f.tenant_id=o.tenant_id AND f.order_id=o.id AND f.deleted=0 AND f.refund_status_code='CONFIRMED'
                    AND f.refund_time<=r.payment_time),0)>=o.payable_amount GROUP BY r.order_id
                ), eligible AS (
                  SELECT o.*,p.fully_paid_time,CASE WHEN o.quality='COMPLETE' AND NOT EXISTS(
                    SELECT 1 FROM order_refund_record f WHERE f.tenant_id=o.tenant_id AND f.order_id=o.id AND f.deleted=0
                    AND f.refund_status_code='CONFIRMED' AND f.refund_amount>0 AND f.refund_time<?)
                    THEN 'PAID' ELSE 'PENDING_REVIEW' END performance_status
                  FROM scoped o JOIN paid p ON p.order_id=o.id WHERE p.fully_paid_time>=? AND p.fully_paid_time<?
                )
                """;
        List<Object> args = new ArrayList<>(List.of(tenant, employee, Timestamp.from(until)));
        args.addAll(scope.args());
        args.addAll(List.of(Timestamp.from(until), Timestamp.from(until), Timestamp.from(from), Timestamp.from(until)));
        var total = jdbc.queryForObject(cte + "SELECT COUNT(*) n,COALESCE(SUM(payable_amount),0) amount,"
                + "COALESCE(SUM(CASE WHEN performance_status='PENDING_REVIEW' THEN 1 ELSE 0 END),0) review FROM eligible",
                (rs, n) -> new Page(rs.getLong("n"), rs.getLong("review") > 0 ? null : rs.getBigDecimal("amount"), List.of()), args.toArray());
        if (step == 0) return total;
        args.add(step); args.add(begin);
        var rows = jdbc.query(cte + "SELECT * FROM eligible ORDER BY fully_paid_time DESC,id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new Row(rs.getString("id"), rs.getString("id"), rs.getString("order_no"),
                        rs.getString("customer_name_snapshot"), rs.getTimestamp("fully_paid_time").toInstant(),
                        rs.getBigDecimal("payable_amount"), rs.getString("performance_status")), args.toArray());
        return new Page(total.total(), total.amount(), rows);
    }

}
