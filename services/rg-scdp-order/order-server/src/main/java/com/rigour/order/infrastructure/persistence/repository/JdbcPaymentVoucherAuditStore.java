package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.PaymentVoucherAuditModels.*;
import com.rigour.order.application.port.out.PaymentVoucherAuditStore;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcPaymentVoucherAuditStore implements PaymentVoucherAuditStore {
    private final JdbcTemplate jdbc;
    private final OrderDataScope scopes;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public JdbcPaymentVoucherAuditStore(JdbcTemplate jdbc, OrderDataScope scopes) { this.jdbc=jdbc; this.scopes=scopes; }
    private static List<String> strings(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(JSON.readValue(value, String[].class));
    }
    @Override public List<Payment> payments(String tenant, String action) {
        var scope=scopes.predicate(action, "o.", null);
        var args=new ArrayList<Object>(List.of(tenant,tenant)); args.addAll(scope.args());
        var result=new LinkedHashMap<String,Payment>();
        jdbc.query("""
            SELECT p.id,p.payment_no,p.order_id,o.order_no,p.customer_name_snapshot,
              o.owner_employee_name_snapshot,p.paid_amount,p.payment_time,p.payment_status_code,
              p.deleted AS payment_deleted,COALESCE(o.deleted,0) AS order_deleted,
              p.transaction_no,p.voucher_keys_json,e.voucher_key,e.voucher_amount,
              e.transaction_no AS voucher_transaction,e.evidence_note
            FROM order_payment_record p
            JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
            LEFT JOIN order_payment_voucher_transaction e ON e.tenant_id=? AND e.payment_id=p.id
            WHERE p.tenant_id=? AND (
            """ + scope.sql() + ") ORDER BY p.id,e.voucher_key", rs -> {
                String id=rs.getString("id");
                if (!result.containsKey(id)) {
                    var time=rs.getObject("payment_time",LocalDateTime.class);
                    String status=rs.getString("payment_status_code");
                    result.put(id,new Payment(id,rs.getString("payment_no"),rs.getString("order_id"),rs.getString("order_no"),
                        rs.getString("customer_name_snapshot"),rs.getString("owner_employee_name_snapshot"),rs.getBigDecimal("paid_amount"),
                        time==null?null:time.toInstant(ZoneOffset.UTC),status,
                        rs.getBoolean("payment_deleted")||rs.getBoolean("order_deleted")||!Set.of("RECEIVED","CHECKED").contains(status),
                        rs.getString("transaction_no"),strings(rs.getString("voucher_keys_json")),new ArrayList<>()));
                }
                String key=rs.getString("voucher_key");
                // 已移除附件的旧识别结果不能继续充当现有回款证据。
                if (key!=null && result.get(id).attachmentKeys().contains(key)) result.get(id).evidence().add(new Evidence(key,
                    rs.getBigDecimal("voucher_amount"),rs.getString("voucher_transaction"),rs.getString("evidence_note")));
            },args.toArray());
        return new ArrayList<>(result.values());
    }
    @Override public Map<String,List<Review>> reviews(String tenant) {
        var result=new HashMap<String,List<Review>>();
        jdbc.query("SELECT * FROM order_payment_voucher_review WHERE tenant_id=? ORDER BY created_time DESC,id DESC",rs -> {
            var r=new Review(rs.getString("id"),rs.getString("fingerprint"),rs.getString("conclusion"),rs.getString("note"),
                rs.getString("actor"),rs.getTimestamp("created_time").toInstant(),strings(rs.getString("payment_ids_json")));
            result.computeIfAbsent(rs.getString("group_key"),k->new ArrayList<>()).add(r);
        },tenant);
        return result;
    }
    @Override public Review appendReview(String tenant,String groupKey,String fingerprint,String conclusion,String note,String actor,List<String> ids) {
        String id=UUID.randomUUID().toString(); Instant now=Instant.now();
        jdbc.update("INSERT INTO order_payment_voucher_review (id,tenant_id,group_key,fingerprint,conclusion,note,actor,payment_ids_json,created_time) VALUES (?,?,?,?,?,?,?,?,?)",
            id,tenant,groupKey,fingerprint,conclusion,note,actor,JSON.writeValueAsString(ids),java.sql.Timestamp.from(now));
        return new Review(id,fingerprint,conclusion,note,actor,now,ids);
    }
}
