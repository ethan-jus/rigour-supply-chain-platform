package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.StoreOrderResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcStoreOrderOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    public JdbcStoreOrderOutbox(JdbcTemplate jdbc,ObjectMapper json,Clock clock) { this.jdbc=jdbc; this.json=json; this.clock=clock; }
    @Transactional
    public void enqueue(String tenant,long order) {
        var rows=jdbc.query("SELECT * FROM order_sales_order WHERE tenant_id=? AND id=? AND store_id IS NOT NULL FOR UPDATE",(rs,n)->{
            var row=new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rs,n);
            row.put("store_effective_at",rs.getTimestamp("store_effective_at"));
            row.put("store_authorization_expires_at",rs.getTimestamp("store_authorization_expires_at"));
            return row;
        },tenant,order);
        if(rows.isEmpty()) return;
        var r=rows.getFirst();
        long sequence=((Number)r.get("crm_fact_revision")).longValue()+1;
        var now=clock.instant();
        var result=new StoreOrderResult(tenant,Long.toString(order),r.get("store_id").toString(),r.get("ownership_id").toString(),
                r.get("store_salesperson_id")==null?null:r.get("store_salesperson_id").toString(),(String)r.get("store_request_id"),sequence,
                (String)r.get("store_submission_status"),(String)r.get("order_status_code"),((Number)r.get("deleted")).intValue()!=0,
                (BigDecimal)r.get("payable_amount"),instant(r.get("store_effective_at")),instant(r.get("store_authorization_expires_at")),now);
        jdbc.update("UPDATE order_sales_order SET crm_fact_revision=? WHERE tenant_id=? AND id=?",sequence,tenant,order);
        jdbc.update("INSERT INTO order_crm_result_outbox(id,tenant_id,order_id,source_revision,payload_json,created_at) VALUES(?,?,?,?,?,?)",
                IdWorker.getId(),tenant,order,sequence,json.writeValueAsString(result),Timestamp.from(now));
    }
    @Transactional
    public void expirePending() {
        var rows=jdbc.queryForList("""
                SELECT tenant_id,id FROM order_sales_order WHERE store_id IS NOT NULL
                AND store_submission_status IN ('PENDING_AUTH','AUTHORIZED') AND store_authorization_expires_at<=?
                ORDER BY store_authorization_expires_at,id LIMIT 100 FOR UPDATE SKIP LOCKED
                """,Timestamp.from(clock.instant()));
        for(var r:rows) {
            String tenant=r.get("tenant_id").toString(); long id=((Number)r.get("id")).longValue();
            jdbc.update("UPDATE order_sales_order SET store_submission_status='EXPIRED' WHERE tenant_id=? AND id=?",tenant,id);
            enqueue(tenant,id);
        }
    }
    private static Instant instant(Object v) { return v==null?null:v instanceof Timestamp t?t.toInstant():((java.time.LocalDateTime)v).toInstant(java.time.ZoneOffset.UTC); }
}
