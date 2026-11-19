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
        var scope = scopes.paymentPredicate(action, "o.", "p.");
        return queryCache.read(tenant, "workbench-performance-contributions", List.of(employee, action, scope, from, until), 10,
                new tools.jackson.core.type.TypeReference<Totals>() {}, () -> {
                    var page = performance(tenant, employee, scope, from, until, 0, 0);
                    return new Totals(page.total(), page.amount());
                });
    }
    @Override @Transactional(readOnly = true)
    public Page details(String tenant, String employee, String action, String kind,
                        Instant from, Instant until, int begin, int step) {
        var scope = ("performance".equals(kind) || "receipts".equals(kind))
                ? scopes.paymentPredicate(action, "o.", "p.") : scopes.predicate(action, "o.", null);
        if ("performance".equals(kind)) return performance(tenant, employee, scope, from, until, begin, step);
        boolean receipt = "receipts".equals(kind);
        String table = " FROM order_sales_order o" + (receipt
                ? " JOIN order_verified_payment_v p ON p.tenant_id=o.tenant_id AND p.order_id=o.id" : "");
        String where = " WHERE o.tenant_id=? AND " + (receipt ? "p.payment_owner_code=?" : "o.owner_employee_code=?") + " AND o.deleted=0"
                + " AND o.order_status_code IN ('SUBMITTED','COMPLETED') AND " + scope.sql();
        List<Object> args = new ArrayList<>(List.of(tenant, employee));
        args.addAll(scope.args());
        if (receipt) where += " AND p.payment_owner_evidence IS NOT NULL AND p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED')";
        String time = receipt ? "p.actual_cash_at" : "o.order_date";
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
    /** 结清月份入账，每位回款人仅计其在结清前的实际贡献，超收不重复计业绩。 */
    private Page performance(String tenant, String employee, OrderDataScope.Predicate scope, Instant from, Instant until, int begin, int step) {
        String cte = """
                WITH scoped AS (
                  SELECT o.* FROM order_sales_order o
                  WHERE o.tenant_id=? AND o.deleted=0 AND o.order_status_code IN ('SUBMITTED','COMPLETED')
                    AND EXISTS(SELECT 1 FROM order_payment_record own WHERE own.tenant_id=o.tenant_id AND own.order_id=o.id
                      AND own.payment_owner_code=? AND own.payment_owner_evidence IS NOT NULL
                      AND own.deleted=0 AND own.payment_status_code IN ('RECEIVED','CHECKED'))
                ), running AS (
                  SELECT p.id,p.tenant_id,p.order_id,p.paid_amount,p.payment_owner_code,p.payment_owner_evidence,
                    p.allocation_effective_at payment_time,
                    SUM(p.paid_amount) OVER(PARTITION BY p.tenant_id,p.order_id ORDER BY p.allocation_effective_at,p.id ROWS UNBOUNDED PRECEDING) cumulative
                  FROM order_verified_payment_v p JOIN scoped o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
                  WHERE p.allocation_effective_at<?
                ), settled AS (
                  SELECT r.order_id,r.payment_time,r.id,
                    ROW_NUMBER() OVER(PARTITION BY r.order_id ORDER BY r.payment_time,r.id) settlement_rank
                  FROM running r JOIN scoped o ON o.id=r.order_id
                  WHERE o.payable_amount>0 AND r.cumulative-COALESCE((SELECT SUM(f.refund_amount) FROM order_refund_record f
                    WHERE f.tenant_id=o.tenant_id AND f.order_id=o.id AND f.deleted=0 AND f.refund_status_code='CONFIRMED'
                    AND f.refund_time<=r.payment_time),0)>=o.payable_amount
                ), eligible AS (
                  SELECT o.id,o.order_no,o.customer_name_snapshot,s.payment_time fully_paid_time,
                    SUM(LEAST(p.paid_amount,GREATEST(o.payable_amount-(p.cumulative-p.paid_amount),0))) contribution,
                    CASE WHEN o.data_quality_status_code='COMPLETE' AND NOT EXISTS(
                      SELECT 1 FROM order_refund_record f WHERE f.tenant_id=o.tenant_id AND f.order_id=o.id AND f.deleted=0
                      AND f.refund_status_code='CONFIRMED' AND f.refund_amount>0 AND f.refund_time<?)
                      AND NOT EXISTS(SELECT 1 FROM order_attribution_snapshot a WHERE a.tenant_id=o.tenant_id AND a.order_id=o.id AND a.state<>'FROZEN')
                      THEN 'PAID' ELSE 'PENDING_REVIEW' END performance_status
                  FROM scoped o JOIN settled s ON s.order_id=o.id AND s.settlement_rank=1
                  JOIN running p ON p.order_id=o.id AND p.tenant_id=o.tenant_id
                    AND (p.payment_time<s.payment_time OR (p.payment_time=s.payment_time AND p.id<=s.id))
                  WHERE s.payment_time>=? AND s.payment_time<? AND p.payment_owner_code=? AND p.payment_owner_evidence IS NOT NULL AND
                """ + scope.sql() + """
                  GROUP BY o.id,o.order_no,o.customer_name_snapshot,s.payment_time,performance_status
                  HAVING contribution>0
                )
                """;
        List<Object> args = new ArrayList<>(List.of(tenant, employee, Timestamp.from(until), Timestamp.from(until),
                Timestamp.from(from), Timestamp.from(until), employee));
        args.addAll(scope.args());
        var total = jdbc.queryForObject(cte + "SELECT COUNT(*) n,COALESCE(SUM(contribution),0) amount,"
                + "COALESCE(SUM(CASE WHEN performance_status='PENDING_REVIEW' THEN 1 ELSE 0 END),0) review FROM eligible",
                (rs, n) -> new Page(rs.getLong("n"), rs.getLong("review") > 0 ? null : rs.getBigDecimal("amount"), List.of()), args.toArray());
        if (step == 0) return total;
        args.add(step); args.add(begin);
        var rows = jdbc.query(cte + "SELECT * FROM eligible ORDER BY fully_paid_time DESC,id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new Row(rs.getString("id"), rs.getString("id"), rs.getString("order_no"),
                        rs.getString("customer_name_snapshot"), rs.getTimestamp("fully_paid_time").toInstant(),
                        rs.getBigDecimal("contribution"), rs.getString("performance_status")), args.toArray());
        return new Page(total.total(), total.amount(), rows);
    }

    private static final String CUSTOMER_KEY = "CASE WHEN o.customer_id IS NOT NULL THEN CONCAT('C',o.customer_id) ELSE CONCAT('S',o.store_id) END";
    private record CustomerQuery(String sql, List<Object> args) {}
    /** 公司首单仅用于分类；返回值与金额始终限制在当前动作的角色数据范围内。 */
    private CustomerQuery customerQuery(String tenant, String employee, OrderDataScope.Predicate scope, Instant from, Instant until) {
        String sql = "WITH history AS (SELECT o.*, " + CUSTOMER_KEY + " customer_key,"
                + " MIN(o.order_date) OVER(PARTITION BY " + CUSTOMER_KEY + ") first_at,"
                + " ROW_NUMBER() OVER(PARTITION BY " + CUSTOMER_KEY + " ORDER BY o.order_date,o.id) company_sequence"
                + " FROM order_sales_order o WHERE o.tenant_id=? AND o.deleted=0"
                + " AND o.order_status_code IN ('SUBMITTED','COMPLETED') AND o.order_date IS NOT NULL"
                + " AND (o.customer_id IS NOT NULL OR o.store_id IS NOT NULL)),"
                + " customers AS (SELECT o.customer_key,MAX(o.customer_name_snapshot) name,COUNT(*) order_count,"
                + " MAX(o.order_date) last_order_at,MIN(o.first_at) first_at,"
                + " SUM(CASE WHEN o.order_date>=? AND o.order_date<? THEN 1 ELSE 0 END) period_orders,"
                + " MAX(CASE WHEN o.company_sequence=1 AND o.order_date>=? AND o.order_date<? THEN 1 ELSE 0 END) newly"
                + " FROM history o WHERE o.owner_employee_code=? AND " + scope.sql() + " GROUP BY o.customer_key) ";
        var args = new ArrayList<Object>(List.of(tenant,Timestamp.from(from),Timestamp.from(until),Timestamp.from(from),Timestamp.from(until),employee));
        args.addAll(scope.args());
        return new CustomerQuery(sql,args);
    }
    @Override @Transactional(readOnly = true)
    public CustomerSummary customerSummary(String tenant, String employee, Instant from, Instant until) {
        var scope = scopes.predicate("order:read", "o.", null);
        return queryCache.read(tenant,"workbench-customers",List.of(employee,scope,from,until),10,
                new tools.jackson.core.type.TypeReference<CustomerSummary>() {}, () -> {
            var query = customerQuery(tenant,employee,scope,from,until);
            var args = new ArrayList<>(query.args()); args.add(Timestamp.from(from));
            return jdbc.queryForObject(query.sql() + "SELECT COUNT(*) cooperated,"
                    + " COALESCE(SUM(CASE WHEN period_orders>0 THEN 1 ELSE 0 END),0) purchasing,"
                    + " COALESCE(SUM(newly),0) newly,"
                    + " COALESCE(SUM(CASE WHEN period_orders>0 AND first_at<? THEN 1 ELSE 0 END),0) returning FROM customers",
                    (r,n) -> new CustomerSummary(r.getLong("purchasing"),r.getLong("newly"),r.getLong("returning"),r.getLong("cooperated")),args.toArray());
        });
    }
    @Override @Transactional(readOnly = true)
    public Customers customers(String tenant, String employee, String kind, Instant from, Instant until, String keyword, int begin, int step) {
        var scope = scopes.predicate("order:read", "o.", null);
        var query = customerQuery(tenant,employee,scope,from,until);
        var args = new ArrayList<>(query.args());
        String where = " WHERE " + switch(kind) {
            case "PURCHASING" -> "period_orders>0";
            case "NEW" -> "newly=1";
            case "RETURNING" -> "period_orders>0 AND first_at<?";
            case "COOPERATED" -> "1=1";
            default -> throw new IllegalArgumentException("客户统计类型无效");
        };
        if ("RETURNING".equals(kind)) args.add(Timestamp.from(from));
        if (!keyword.isBlank()) { where += " AND LOCATE(?,name)>0"; args.add(keyword); }
        long total = jdbc.queryForObject(query.sql()+"SELECT COUNT(*) FROM customers"+where,Long.class,args.toArray());
        args.add(step); args.add(begin);
        var rows = jdbc.query(query.sql()+"SELECT * FROM customers"+where+" ORDER BY last_order_at DESC,customer_key LIMIT ? OFFSET ?",
                (r,n) -> new CustomerRow(r.getString("customer_key"),r.getString("name"),r.getLong("order_count"),r.getTimestamp("last_order_at").toInstant()),args.toArray());
        return new Customers(total,rows);
    }
    @Override @Transactional(readOnly = true)
    public Page customerOrders(String tenant, String employee, String customerKey, int begin, int step) {
        var scope = scopes.predicate("order:read","o.",null);
        var args = new ArrayList<Object>(List.of(tenant,employee,customerKey.substring(1)));
        // 区分客户与尚未关联订货客户的统一门店，绝不靠名称拼接历史身份。
        String identity = customerKey.startsWith("C") ? "o.customer_id=?" : "o.customer_id IS NULL AND o.store_id=?";
        String where = " FROM order_sales_order o WHERE o.tenant_id=? AND o.owner_employee_code=? AND " + identity
                + " AND o.deleted=0 AND o.order_status_code IN ('SUBMITTED','COMPLETED') AND " + scope.sql();
        args.addAll(scope.args());
        var total = jdbc.queryForObject("SELECT COUNT(*) n,COALESCE(SUM(o.payable_amount),0) amount"+where,
                (r,n) -> new Page(r.getLong("n"),r.getBigDecimal("amount"),List.of()),args.toArray());
        args.add(step); args.add(begin);
        var rows = jdbc.query("SELECT o.*"+where+" ORDER BY o.order_date DESC,o.id DESC LIMIT ? OFFSET ?",
                (r,n) -> new Row(r.getString("id"),r.getString("id"),r.getString("order_no"),r.getString("customer_name_snapshot"),
                        r.getTimestamp("order_date")==null?null:r.getTimestamp("order_date").toInstant(),r.getBigDecimal("payable_amount"),r.getString("payment_status_code")),args.toArray());
        return new Page(total.total(),total.amount(),rows);
    }
}
