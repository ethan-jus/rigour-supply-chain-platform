package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
import com.rigour.merchant.api.v1.model.StoreOrderResult;
import com.rigour.merchant.application.port.out.*;
import com.rigour.merchant.domain.store.VisitEvidenceAssessment;
import com.rigour.order.api.v1.model.StoreOrderFacts;
import com.rigour.shared.core.exception.StateConflictException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcStoreFactStore implements StoreFactStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JdbcStoreLifecycleStore lifecycle;
    public JdbcStoreFactStore(JdbcTemplate jdbc,ObjectMapper json,JdbcStoreLifecycleStore lifecycle) {
        this.jdbc=jdbc; this.json=json; this.lifecycle=lifecycle;
    }
    private void lock(String tenant,String store) {
        if(jdbc.queryForList("SELECT id FROM crm_store_master WHERE tenant_id=? AND id=? FOR UPDATE",tenant,store).isEmpty())
            throw new StateConflictException("同步门店不存在");
    }
    @Override @Transactional
    public void acceptEvidence(Evidence e,Instant now) {
        lock(e.tenantId(),e.storeId());
        var rows=jdbc.query("SELECT v.*,o.deadline_at FROM crm_visit v JOIN crm_store_ownership o ON o.tenant_id=v.tenant_id AND o.id=v.ownership_id WHERE v.tenant_id=? AND v.id=?",(rs,n)->{
            var row=new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rs,n);
            row.put("checkin_accepted_at",rs.getTimestamp("checkin_accepted_at"));
            row.put("deadline_at",rs.getTimestamp("deadline_at"));
            return row;
        },e.tenantId(),e.visitId());
        if(rows.isEmpty()) throw new StateConflictException("CRM 尚未受理该拜访");
        var v=rows.getFirst();
        if(!e.storeId().equals(v.get("store_id").toString()) || !e.ownershipId().equals(v.get("ownership_id").toString())
                || !e.captureRef().equals(v.get("capture_ref")) || !e.employeeCode().equals(v.get("employee_code_snapshot"))
                || !e.policyId().equals(v.get("validation_policy_version"))
                || !e.checkedInAt().equals(instant(v.get("checkin_accepted_at"))))
            throw new StateConflictException("采集结果与 CRM 拜访身份不一致");
        if(v.get("capture_revision")!=null && ((Number)v.get("capture_revision")).longValue()>=e.revision()) return;
        Policy policy;
        if(v.get("policy_snapshot")!=null) policy=json.readValue(v.get("policy_snapshot").toString(),Policy.class);
        else {
            var policies=jdbc.queryForList("SELECT policy_json FROM crm_visit_policy_version WHERE tenant_id=? AND id=?",e.tenantId(),e.policyId());
            if(policies.isEmpty()) throw new StateConflictException("历史拜访政策尚未迁入 CRM");
            policy=json.readValue(policies.getFirst().get("policy_json").toString(),Policy.class);
        }
        jdbc.update("UPDATE crm_visit SET capture_revision=?,capture_synced_at=?,policy_snapshot=COALESCE(policy_snapshot,?) WHERE tenant_id=? AND id=?",
                e.revision(),ts(e.checkedAt()),json.writeValueAsString(policy),e.tenantId(),e.visitId());
        if("MANUAL".equals(v.get("review_source")) || Set.of("CANCELLED").contains(Objects.toString(v.get("review_source"),""))) return;
        // A result submitted before checkout is already pending review; keep that
        // receipt so a later capture heartbeat cannot make the cycle look idle.
        if(e.submittedAt()==null) return;
        var anomalies=VisitEvidenceAssessment.anomalies(policy,e);
        boolean late=v.get("submitted_at")==null && !e.submittedAt().isBefore(instant(v.get("deadline_at")));
        String status=late?"INVALID":anomalies.isEmpty()?"EFFECTIVE":"SUBMITTED";
        String reason=late?"SUBMISSION_AFTER_DEADLINE":anomalies.isEmpty()?"AUTO_EVIDENCE_COMPLETE":String.join(",",anomalies);
        boolean changed=!status.equals(v.get("status_code")) || !reason.equals(v.get("review_reason"))
                || !Objects.equals(e.resultSummary(),v.get("result_summary"));
        if(!changed) return;
        jdbc.update("""
                UPDATE crm_visit SET status_code=?,result_summary=?,checked_out_at=?,submitted_at=?,
                qualified_at=IF(?='EFFECTIVE',checkin_accepted_at,NULL),review_source='AUTOMATIC',
                review_reason=?,reviewed_at=?,revision=revision+1,updated_at=?,updated_by='SALES_EVIDENCE'
                WHERE tenant_id=? AND id=?
                """,status,e.resultSummary(),ts(e.checkedOutAt()),ts(e.submittedAt()),status,reason,ts(now),ts(now),e.tenantId(),e.visitId());
        jdbc.update("""
                UPDATE crm_store_summary SET
                valid_visit_count=(SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),
                first_valid_visit_at=(SELECT MIN(visited_at) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),
                last_visit_at=(SELECT MAX(visited_at) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),
                visit_synced_at=?,revision=revision+1 WHERE tenant_id=? AND store_id=?
                """,e.tenantId(),e.storeId(),e.tenantId(),e.storeId(),e.tenantId(),e.storeId(),ts(now),e.tenantId(),e.storeId());
        jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,ownership_id,object_type,object_id,event_type,actor_user_id,occurred_at,reason,request_id,event_seq) VALUES(?,?,?,?,'VISIT',?,?,'SALES_EVIDENCE',?,?,?,1)",
                IdWorker.getId(),e.tenantId(),e.storeId(),e.ownershipId(),e.visitId(),"VISIT_"+status,ts(now),reason,UUID.randomUUID().toString());
        refresh(e.tenantId(),e.storeId(),now);
    }
    @Override @Transactional
    public void acceptOrder(StoreOrderResult r,Instant now) {
        lock(r.tenantId(),r.storeId());
        var owners=jdbc.queryForList("SELECT owner_employee_id FROM crm_store_ownership WHERE tenant_id=? AND id=? AND store_id=?",r.tenantId(),r.ownershipId(),r.storeId());
        if(owners.isEmpty() || r.salespersonId()!=null && !r.salespersonId().equals(owners.getFirst().get("owner_employee_id").toString()))
            throw new StateConflictException("订单归属快照与 CRM 不一致");
        var prior=jdbc.queryForList("SELECT source_sequence,store_id,ownership_id FROM crm_store_order_fact WHERE tenant_id=? AND order_id=?",r.tenantId(),r.orderId());
        if(!prior.isEmpty()) {
            var old=prior.getFirst();
            if(!r.storeId().equals(old.get("store_id").toString()) || !r.ownershipId().equals(old.get("ownership_id").toString()))
                throw new StateConflictException("订单不能更换既有门店或归属周期");
            if(((Number)old.get("source_sequence")).longValue()>=r.sequence()) return;
        }
        boolean valid=!r.deleted() && "COMPLETED".equals(r.submissionStatus())
                && Set.of("SUBMITTED","COMPLETED").contains(r.orderStatus()) && r.payableAmount().signum()>0 && r.effectiveAt()!=null;
        boolean pending=!r.deleted() && "DRAFT".equals(r.orderStatus())
                && Set.of("PENDING_AUTH","AUTHORIZED").contains(r.submissionStatus());
        jdbc.update("""
                INSERT INTO crm_store_order_fact(tenant_id,order_id,store_id,ownership_id,salesperson_id,
                source_sequence,effective_at,valid_order,pending_authorization,observed_at,snapshot_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE salesperson_id=VALUES(salesperson_id),source_sequence=VALUES(source_sequence),
                effective_at=VALUES(effective_at),valid_order=VALUES(valid_order),
                pending_authorization=VALUES(pending_authorization),observed_at=VALUES(observed_at),snapshot_json=VALUES(snapshot_json)
                """,r.tenantId(),r.orderId(),r.storeId(),r.ownershipId(),r.salespersonId(),r.sequence(),ts(r.effectiveAt()),
                valid,pending,ts(r.observedAt()),json.writeValueAsString(r));
        if(!pending) jdbc.update("UPDATE crm_store_order_request SET resolved_at=? WHERE tenant_id=? AND order_id=? AND request_id=?",
                ts(now),r.tenantId(),r.orderId(),r.requestId());
        if(lifecycle.current(r.tenantId(),r.storeId())==null) {
            var dates=orderDates(r.tenantId(),r.storeId());
            lifecycle.projectOrders(r.tenantId(),r.storeId(),instant(dates.get("first_at")),instant(dates.get("last_at")),now);
            jdbc.update("UPDATE crm_store_summary SET revision=revision+1 WHERE tenant_id=? AND store_id=?",r.tenantId(),r.storeId());
        } else refresh(r.tenantId(),r.storeId(),now);
    }
    private void refresh(String tenant,String store,Instant now) {
        var cycle=lifecycle.current(tenant,store);
        if(cycle!=null) lifecycle.reconcile(cycle,orders(cycle,now),now);
    }
    @Override public StoreOrderFacts orders(StoreLifecycleStore.Cycle c,Instant now) {
        var facts=jdbc.query("SELECT order_id,ownership_id,salesperson_id,effective_at,source_sequence FROM crm_store_order_fact WHERE tenant_id=? AND ownership_id=? AND valid_order=1 ORDER BY effective_at DESC,order_id DESC LIMIT 1",
                (r,n)->new StoreOrderFacts.Fact(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant(),r.getLong(5)),c.tenant(),c.id());
        var dates=orderDates(c.tenant(),c.storeId());
        boolean pending=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM crm_store_order_request WHERE tenant_id=? AND ownership_id=? AND resolved_at IS NULL)+(SELECT COUNT(*) FROM crm_store_order_fact WHERE tenant_id=? AND ownership_id=? AND pending_authorization=1)",Long.class,c.tenant(),c.id(),c.tenant(),c.id())>0;
        return new StoreOrderFacts(c.tenant(),c.storeId(),c.id(),now,"STORE_ORDER_V1",facts,pending,instant(dates.get("first_at")),instant(dates.get("last_at")));
    }
    private Map<String,Object> orderDates(String tenant,String store) {
        return jdbc.queryForObject("SELECT MIN(effective_at) first_at,MAX(effective_at) last_at FROM crm_store_order_fact WHERE tenant_id=? AND store_id=? AND valid_order=1",(rs,n)->{
            Map<String,Object> dates=new HashMap<>();dates.put("first_at",rs.getTimestamp("first_at"));dates.put("last_at",rs.getTimestamp("last_at"));return dates;
        },tenant,store);
    }
    private static Timestamp ts(Instant value) { return value==null?null:Timestamp.from(value); }
    private static Instant instant(Object value) { return value==null?null:value instanceof Timestamp t?t.toInstant():((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC); }
}
