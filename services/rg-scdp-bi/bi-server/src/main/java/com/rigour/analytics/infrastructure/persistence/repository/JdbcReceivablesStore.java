package com.rigour.analytics.infrastructure.persistence.repository;

import static com.rigour.analytics.infrastructure.persistence.scope.OperatingCitySql.JDBC_REGION_SCOPE;

import com.rigour.analytics.api.v1.model.ReceivablesView.*;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.port.out.ReceivablesStore;
import com.rigour.analytics.infrastructure.persistence.scope.BiScopedQueries;

import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

@Repository
public class JdbcReceivablesStore implements ReceivablesStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReceivablesStore(DataSource source) {
        jdbc = new NamedParameterJdbcTemplate(source);
    }

    static final String ORDERS =
            """
            WITH visible_orders AS (
              SELECT o.* FROM bi_sales_order_fact o WHERE o.tenant_id=:tenant AND o.deleted=0
               AND o.order_status_code NOT IN ('DRAFT','CANCELLED','DELETED')
               AND (:customer IS NULL OR o.customer_id=:customer)
               AND (:owner IS NULL OR o.owner_staff_code=:owner)
               AND (:region IS NULL OR o.region_code COLLATE utf8mb4_general_ci\
            """
                    + JDBC_REGION_SCOPE
                    + """
                    )
               AND (:departmentId IS NULL OR EXISTS (
                   SELECT 1 FROM bi_order_authority a
                   WHERE a.tenant_id=o.tenant_id AND a.order_id=o.order_id
                     AND (a.department_id=:departmentId
                          OR (:includeSubDepartments AND JSON_CONTAINS(a.department_path,CAST(:departmentId AS JSON))))
               )))
                    """;
    static final String VALID_APPLICATIONS =
            """
, applied AS (
  SELECT a.*,r.reference_no FROM bi_cash_application a
  JOIN visible_orders o ON o.tenant_id COLLATE utf8mb4_general_ci=a.tenant_id AND o.order_id=a.order_id
  JOIN bi_cash_receipt r ON r.tenant_id COLLATE utf8mb4_general_ci=a.tenant_id AND r.id=UNHEX(a.cash_key)
  WHERE a.tenant_id=:tenant AND a.verified=1 AND r.verified=1 AND r.cash_at<:until AND a.effective_at<:until
)
""";

    static MapSqlParameterSource args(Scope s, LocalDate until) {
        return new MapSqlParameterSource("tenant", s.tenant())
                .addValue("region", s.region())
                .addValue("owner", s.owner())
                .addValue("customer", s.customer())
                .addValue("full", s.full())
                .addValue("departmentId", s.departmentId())
                .addValue("includeSubDepartments", s.includeSubDepartments())
                .addValue("until", boundary(until.plusDays(1)))
                .addValue("asOf", java.sql.Date.valueOf(until));
    }

    static Timestamp boundary(LocalDate date) {
        return Timestamp.from(date.atStartOfDay(BiBusinessTime.ZONE).toInstant());
    }

    @Override
    public Instant projectedAt(String tenant) {
        var t =
                jdbc.queryForObject(
                        """
SELECT IF(COUNT(*)=5,MIN(projected_at),NULL) FROM bi_source_snapshot_checkpoint
WHERE tenant_id=:tenant AND source_version IS NOT NULL AND source_version<>''
 AND dataset IN ('ORDER_CASH_RECEIPT','ORDER_CASH_APPLICATION','ORDER_RECEIVABLE_PLAN','ORDER_RECEIVABLE_ADJUSTMENT','ORDER_RECEIVABLE_OPENING')
""",
                        new MapSqlParameterSource("tenant", tenant),
                        Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    private static final String BALANCE_INPUTS = """
, balances AS (
     SELECT e.order_id,SUM(e.paid) paid,SUM(e.adjustment) delta
     FROM bi_order_balance_event e JOIN visible_orders o
       ON o.tenant_id COLLATE utf8mb4_general_ci=e.tenant_id AND o.order_id=e.order_id
     WHERE e.effective_at<:until GROUP BY e.order_id)
, current_plan AS (
   SELECT i.order_id,MAX(i.plan_version) version FROM bi_receivable_installment i
   JOIN visible_orders o ON o.tenant_id COLLATE utf8mb4_general_ci=i.tenant_id AND o.order_id=i.order_id
   WHERE i.effective_at<:until GROUP BY i.order_id)
""";

    private static final String BALANCE_COLUMNS = """
        COALESCE(i.installment_no,1) pos,COALESCE(i.amount,o.payable_amount) nominal,
        COALESCE(i.due_date,DATE(DATE_ADD(TIMESTAMPADD(HOUR,8,o.order_date), INTERVAL policy.payment_term_days DAY))) due,
        COALESCE(b.paid,0) paid,COALESCE(b.delta,0) delta,
        ROW_NUMBER() OVER(PARTITION BY o.order_id ORDER BY i.installment_no DESC) reverse_pos
""";

    private static final String BALANCE_JOINS = """
   LEFT JOIN current_plan v ON v.order_id=o.order_id
   LEFT JOIN bi_receivable_installment i ON i.tenant_id COLLATE utf8mb4_general_ci=o.tenant_id AND i.order_id=o.order_id AND i.plan_version=v.version
   LEFT JOIN bi_source_crm_crm_customer c ON c.tenant_id=:tenant AND c.id=o.customer_id
   LEFT JOIN bi_source_crm_crm_customer_policy policy ON policy.tenant_id=UUID_TO_BIN(o.tenant_id)
       AND policy.party_id=c.party_id AND policy.deleted=0 AND policy.status='ACTIVE'
       AND policy.updated_time<=o.order_date
   WHERE o.order_date<:until AND o.payable_amount>0)
""";

    private static final String SPREAD_BALANCES = """
, portions AS (
   SELECT sheet.*,nominal+IF(reverse_pos=1,GREATEST(delta,0),0) face,paid+GREATEST(-delta,0) credit FROM sheet)
, spread AS (
   SELECT portions.*,SUM(face) OVER(PARTITION BY order_id ORDER BY pos ROWS UNBOUNDED PRECEDING) upto FROM portions)
""";
    private static final String REMAINING = "LEAST(face,GREATEST(0,upto-credit))";

    @Override
    public List<Balance> balances(Scope s, LocalDate asOf) {
        return BiScopedQueries.query(
                jdbc,
                ORDERS + BALANCE_INPUTS + """
, follow_history AS (
   SELECT o.order_id,e.assignee,e.promised_on,e.disputed,e.occurred_at,e.due_at,e.note,e.status,
          ROW_NUMBER() OVER(PARTITION BY o.order_id ORDER BY e.occurred_at DESC,e.id DESC) rn
   FROM visible_orders o JOIN bi_operating_action a ON a.tenant_id COLLATE utf8mb4_general_ci=o.tenant_id
       AND a.kind='COLLECTION' AND a.business_ref COLLATE utf8mb4_general_ci=CONCAT('order-id:',o.order_id)
   JOIN bi_operating_action_event e ON e.tenant_id=a.tenant_id AND e.action_id=a.id AND e.occurred_at<:until)
, sheet AS (
   SELECT o.order_id,o.order_no,o.customer_id,o.customer_name,o.region_name,o.region_code,
        o.owner_staff_name,o.owner_staff_code,o.payable_amount original,
        fol.assignee,fol.promised_on,fol.disputed,fol.occurred_at,IF(fol.status IN ('OPEN','IN_PROGRESS'),fol.due_at,NULL) next_due,fol.note,
        CASE WHEN i.id IS NOT NULL THEN '合同分期' WHEN policy.payment_term_days IS NOT NULL THEN '客户约定账期' ELSE '待核' END due_source,
""" + BALANCE_COLUMNS + """
   FROM visible_orders o LEFT JOIN balances b ON b.order_id=o.order_id
   LEFT JOIN follow_history fol ON fol.order_id=o.order_id AND fol.rn=1
""" + BALANCE_JOINS + SPREAD_BALANCES + "SELECT *, " + REMAINING + """
 remaining FROM spread ORDER BY due IS NULL,due,order_id,pos
""",
                args(s, asOf),
                (r, n) -> {
                    LocalDate due = r.getObject("due", LocalDate.class);
                    Long days =
                            due == null
                                    ? null
                                    : java.time.temporal.ChronoUnit.DAYS.between(due, asOf);
                    String bucket =
                            days == null
                                    ? "UNKNOWN"
                                    : days <= 0
                                            ? "CURRENT"
                                            : days <= 30
                                                    ? "D1_30"
                                                    : days <= 60
                                                            ? "D31_60"
                                                            : days <= 90 ? "D61_90" : "OVER90";
                    return new Balance(
                            r.getString("order_id"),
                            r.getString("order_no"),
                            r.getString("customer_id"),
                            r.getString("customer_name"),
                            r.getString("region_name"),
                            r.getString("owner_staff_name"),
                            r.getString("owner_staff_code"),
                            r.getString("region_code"),
                            r.getInt("pos"),
                            due,
                            r.getString("due_source"),
                            r.getBigDecimal("original"),
                            r.getBigDecimal("face"),
                            r.getBigDecimal("paid"),
                            r.getBigDecimal("remaining"),
                            bucket,
                            days,
                            r.getString("assignee"),
                            r.getObject("promised_on", LocalDate.class),
                            r.getObject("disputed") == null ? null : r.getBoolean("disputed"),
                            instant(r.getTimestamp("occurred_at")),
                            instant(r.getTimestamp("next_due")),
                            r.getString("note"));
                });
    }

    private static Instant instant(Timestamp time) {
        return time == null ? null : time.toInstant();
    }

    @Override
    public List<OverviewRow> overview(Scope s, LocalDate from, LocalDate asOf) {
        // 共用分期应收余额公式。看板只读金额，不读取逐单跟进、凭据元数据和客户显示字段。
        return BiScopedQueries.query(jdbc, ORDERS + BALANCE_INPUTS + """
            , sheet AS (
               SELECT o.order_id,
            """ + BALANCE_COLUMNS + """
               FROM visible_orders o LEFT JOIN balances b ON b.order_id=o.order_id
            """ + BALANCE_JOINS + SPREAD_BALANCES + """
            , period_receipts AS (
                SELECT id,cash_at,amount FROM bi_cash_receipt
                 WHERE tenant_id=:tenant AND cash_at>=:from AND cash_at<:until
                   AND verified=1 AND kind='RECEIPT' AND (:customer IS NULL OR customer_id=:customer)
            ), allocations AS (
                SELECT r.id,SUM(a.amount) applied
                  FROM period_receipts r JOIN bi_cash_application a
                    ON a.tenant_id=:tenant AND a.cash_key=LOWER(HEX(r.id))
                  JOIN visible_orders o ON o.order_id=a.order_id
                 WHERE NOT :full AND a.verified=1 AND a.effective_at<:until GROUP BY r.id
            ), remaining AS (
                SELECT due,
            """ + REMAINING + """
                 amount FROM spread
            )
            SELECT NULL cash_date,0 received,COALESCE(SUM(amount),0) outstanding,
                   COALESCE(SUM(CASE WHEN due<:asOf THEN amount ELSE 0 END),0) overdue
              FROM remaining
            UNION ALL
            SELECT DATE(TIMESTAMPADD(HOUR,8,r.cash_at)),
                   SUM(IF(:full,r.amount,LEAST(r.amount,a.applied))),0,0
              FROM period_receipts r LEFT JOIN allocations a ON a.id=r.id
             WHERE :full OR a.applied>0
             GROUP BY DATE(TIMESTAMPADD(HOUR,8,r.cash_at))
            ORDER BY cash_date
            """, args(s, asOf).addValue("from", boundary(from)),
                (r, n) -> new OverviewRow(r.getObject("cash_date", LocalDate.class),
                        r.getBigDecimal("received"), r.getBigDecimal("outstanding"), r.getBigDecimal("overdue")));
    }

    @Override
    public List<Receipt> receipts(Scope s, LocalDate asOf) {
        return BiScopedQueries.query(
                jdbc,
                ORDERS
                        + """
, allocations AS (
  SELECT a.cash_key, SUM(a.amount) scoped_amount,
    SUM(IF(a.effective_at<:until AND a.verified=1,a.amount,0)) applied
  FROM bi_cash_application a JOIN visible_orders o ON o.tenant_id COLLATE utf8mb4_general_ci=a.tenant_id AND o.order_id=a.order_id
  WHERE a.tenant_id=:tenant AND a.effective_at<:until GROUP BY a.cash_key)
SELECT LOWER(HEX(r.id)) id,r.reference_no,r.customer_id,c.customer_name,
    DATE(TIMESTAMPADD(HOUR,8,r.cash_at)) cash_date,r.kind,r.verified,r.channel,r.payee,r.owner_code,r.verified_at,
    IF(:full OR r.kind='REFUND',r.amount,LEAST(r.amount,IF(r.verified=1,a.applied,a.scoped_amount))) amount,
    IF(r.verified=1,COALESCE(a.applied,0),0) applied
FROM bi_cash_receipt r
LEFT JOIN allocations a ON a.cash_key COLLATE utf8mb4_general_ci=LOWER(HEX(r.id))
LEFT JOIN bi_customer_dim c ON c.tenant_id COLLATE utf8mb4_general_ci=r.tenant_id AND c.customer_id=r.customer_id
WHERE r.tenant_id=:tenant AND r.cash_at<:until AND (:customer IS NULL OR r.customer_id=:customer)
  AND (:full OR (a.cash_key IS NOT NULL AND (r.verified=0 OR a.applied>0)) OR (r.kind='REFUND' AND EXISTS(
     SELECT 1 FROM bi_source_order_order_refund_record rf JOIN visible_orders o ON o.tenant_id COLLATE utf8mb4_general_ci=rf.tenant_id AND o.order_id=rf.order_id
     WHERE rf.tenant_id COLLATE utf8mb4_general_ci=r.tenant_id AND r.id=UNHEX(MD5(CONCAT('refund:',rf.id))))))
ORDER BY r.cash_at DESC,r.id
""",
                args(s, asOf),
                (r, n) -> {
                    boolean verified = r.getBoolean("verified");
                    var amount = r.getBigDecimal("amount");
                    var applied = r.getBigDecimal("applied");
                    String kind = r.getString("kind");
                    return new Receipt(
                            r.getString("id"),
                            r.getString("reference_no"),
                            r.getString("customer_id"),
                            r.getString("customer_name"),
                            r.getObject("cash_date", LocalDate.class),
                            kind,
                            verified,
                            amount,
                            applied,
                            s.full() && verified && "RECEIPT".equals(kind)
                                    ? amount.subtract(applied).max(BigDecimal.ZERO)
                                    : BigDecimal.ZERO,
                            r.getString("channel"),
                            r.getString("payee"),
                            r.getString("owner_code"),
                            instant(r.getTimestamp("verified_at")));
                });
    }

    @Override
    public List<Application> applications(Scope s, LocalDate from, LocalDate asOf) {
        return BiScopedQueries.query(
                jdbc,
                ORDERS
                        + VALID_APPLICATIONS
                        + """
SELECT a.id,a.cash_key,a.reference_no,a.order_id,o.order_no,DATE(TIMESTAMPADD(HOUR,8,a.effective_at)) applied_date,a.amount
  FROM applied a JOIN visible_orders o ON o.order_id=a.order_id
 WHERE a.effective_at>=:from ORDER BY a.effective_at DESC,a.id
""",
                args(s, asOf).addValue("from", boundary(from)),
                (r, n) ->
                        new Application(
                                r.getString(1),
                                r.getString(2),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                r.getObject(6, LocalDate.class),
                                r.getBigDecimal(7)));
    }

    @Override
    public BigDecimal adjustments(Scope s, LocalDate from, LocalDate asOf) {
        return BiScopedQueries.queryForObject(
                jdbc,
                ORDERS
                        + """
SELECT COALESCE((SELECT SUM(c.receivable_delta-c.received_delta) FROM bi_receivable_adjustment c
  JOIN visible_orders o ON o.tenant_id COLLATE utf8mb4_general_ci=c.tenant_id AND o.order_id=c.order_id
  WHERE c.deleted=0 AND c.effective_at>=:from AND c.effective_at<:until
  AND c.event_type IN ('CREDIT_NOTE','WRITE_OFF','DEBIT_NOTE','PAYMENT_REVERSAL','CREDIT_REVERSAL')),0)
  - COALESCE((SELECT SUM(h.opening_paid) FROM bi_receivable_opening h
  JOIN visible_orders o ON o.tenant_id COLLATE utf8mb4_general_ci=h.tenant_id AND o.order_id=h.id
  WHERE h.baseline_at>=:from AND h.baseline_at<:until),0)
""",
                args(s, asOf).addValue("from", boundary(from)),
                (rs, index) -> rs.getBigDecimal(1));
    }

    @Override
    public BigDecimal newReceivables(Scope s, LocalDate from, LocalDate asOf) {
        return BiScopedQueries.queryForObject(
                jdbc,
                ORDERS
                        + """
SELECT COALESCE(SUM(payable_amount),0) FROM visible_orders WHERE order_date>=:from AND order_date<:until AND payable_amount>0
""",
                args(s, asOf).addValue("from", boundary(from)),
                (rs, index) -> rs.getBigDecimal(1));
    }
}
