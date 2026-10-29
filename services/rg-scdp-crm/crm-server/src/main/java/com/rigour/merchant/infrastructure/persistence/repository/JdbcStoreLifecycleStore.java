package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.StoreWorkModels.RefreshResult;
import com.rigour.merchant.application.port.out.StoreLifecycleStore;
import com.rigour.merchant.domain.store.OwnershipProtection;
import com.rigour.merchant.domain.store.OwnershipProtection.Policy;
import com.rigour.order.api.v1.model.StoreOrderFacts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStoreLifecycleStore implements StoreLifecycleStore {
    private static final String CYCLES = "SELECT o.*,p.first_visit_hours,p.followup_days,p.cooperating_days,p.return_cooldown_hours FROM crm_store_ownership o JOIN crm_pool_policy p ON p.tenant_id=o.tenant_id AND p.id=o.policy_id";
    private final JdbcTemplate jdbc;
    public JdbcStoreLifecycleStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public boolean hasCapture(Cycle c){return jdbc.queryForObject("SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND ownership_id=? AND capture_ref IS NOT NULL",Long.class,c.tenant(),c.id())>0;}
    @Override public List<Cycle> pending(int limit, Instant now) {
        return jdbc.query(CYCLES+" WHERE o.ended_at IS NULL AND o.next_check_at<=? ORDER BY o.next_check_at,o.id LIMIT ?",this::cycle,ts(now),Math.min(limit,100));
    }
    @Override public Cycle current(String tenant,String store) {
        var rows=jdbc.query(CYCLES+" WHERE o.tenant_id=? AND o.store_id=? AND o.ended_at IS NULL",this::cycle,tenant,store);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Cycle cycle(ResultSet r,int n)throws SQLException {
        return new Cycle(r.getString("tenant_id"),r.getString("store_id"),r.getString("id"),r.getString("owner_employee_id"),r.getLong("revision"),r.getTimestamp("started_at").toInstant(),r.getTimestamp("deadline_at").toInstant(),new Policy(r.getInt("first_visit_hours"),r.getInt("followup_days"),r.getInt("cooperating_days"),r.getInt("return_cooldown_hours")));
    }
    private boolean unchanged(Cycle expected) {
        jdbc.queryForList("SELECT id FROM crm_store_master WHERE tenant_id=? AND id=? FOR UPDATE",expected.tenant(),expected.storeId());
        var actual=current(expected.tenant(),expected.storeId());
        return actual!=null&&actual.id().equals(expected.id())&&actual.revision()==expected.revision();
    }
    private RefreshResult stale(Cycle c) { return new RefreshResult(c.id(),"RETRY",null,null,null); }
    @Override @Transactional public RefreshResult hold(Cycle c,Instant now) {
        if(!unchanged(c))return stale(c);
        return setHold(c,now,"SOURCE_UNAVAILABLE");
    }
    private RefreshResult setHold(Cycle c,Instant now,String reason) {
        int changed=jdbc.update("UPDATE crm_store_ownership SET hold_reason=?,hold_until=?,next_check_at=?,updated_at=? WHERE tenant_id=? AND id=? AND (hold_reason IS NULL OR hold_reason<>?)",reason,ts(now.plusSeconds(60)),ts(now.plusSeconds(60)),ts(now),c.tenant(),c.id(),reason);
        jdbc.update("UPDATE crm_store_ownership SET hold_until=?,next_check_at=? WHERE tenant_id=? AND id=?",ts(now.plusSeconds(60)),ts(now.plusSeconds(60)),c.tenant(),c.id());
        if(changed>0)event(c,"RECOVERY_HELD",reason,now);
        return new RefreshResult(c.id(),"HOLD",null,c.deadline(),reason);
    }
    @Override @Transactional public RefreshResult reconcile(Cycle c,StoreOrderFacts facts,com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Facts captures,Instant now) {
        if(!unchanged(c))return stale(c);
        if(!c.tenant().equals(facts.tenantId())||!c.storeId().equals(facts.storeId())||!c.id().equals(facts.ownershipId()))throw new IllegalArgumentException("订单事实范围错误");
        applyCaptures(c,captures,now);
        var visits=jdbc.queryForList("SELECT id,qualified_at FROM crm_visit WHERE tenant_id=? AND ownership_id=? AND salesperson_id=? AND status_code='EFFECTIVE' ORDER BY qualified_at DESC,id DESC LIMIT 1",c.tenant(),c.id(),c.employeeId());
        String visitId=visits.isEmpty()?null:visits.getFirst().get("id").toString();
        Instant visit=visits.isEmpty()?null:instant(visits.getFirst().get("qualified_at"));
        var order=facts.orders().stream().filter(f->c.id().equals(f.ownershipId())&&c.employeeId().equals(f.salespersonId()))
                .filter(f->f.effectiveAt()!=null&&!f.effectiveAt().isBefore(c.startedAt())&&!f.effectiveAt().isAfter(now))
                .max(java.util.Comparator.comparing(StoreOrderFacts.Fact::effectiveAt)).orElse(null);
        var window=OwnershipProtection.calculate(c.policy(),c.startedAt(),visit,order==null?null:order.effectiveAt());
        // 只投影已确认的新链路事实，不把尚未迁移的订单窗口标为 COMPLETE。
        jdbc.update("UPDATE crm_store_summary q JOIN crm_store_master s ON s.tenant_id=q.tenant_id AND s.id=q.store_id SET q.cooperation_status=IF(s.historical_cooperation_confirmed OR ? IS NOT NULL,'COOPERATED','NEVER'),q.first_cooperated_at=IF(s.historical_cooperation_confirmed,IF(s.historical_first_cooperated_at IS NULL,NULL,LEAST(s.historical_first_cooperated_at,COALESCE(?,s.historical_first_cooperated_at))),?),q.last_order_at=?,q.order_synced_at=?,q.activity_status=IF(? IS NOT NULL AND ?>?,'ACTIVE','UNKNOWN'),q.activity_expires_at=? WHERE q.tenant_id=? AND q.store_id=?",ts(facts.firstStoreOrderAt()),ts(facts.firstStoreOrderAt()),ts(facts.firstStoreOrderAt()),ts(facts.lastStoreOrderAt()),ts(now),ts(facts.lastStoreOrderAt()),ts(facts.lastStoreOrderAt()==null?null:facts.lastStoreOrderAt().plusSeconds(60*86400L)),ts(now),ts(facts.lastStoreOrderAt()==null?null:facts.lastStoreOrderAt().plusSeconds(60*86400L)),c.tenant(),c.storeId());
        jdbc.update("UPDATE crm_store_ownership SET phase=?,deadline_at=?,last_valid_visit_id=?,last_valid_visit_at=?,last_valid_order_id=?,last_valid_order_at=?,revision=revision+1,updated_at=?,updated_by='CRM_LIFECYCLE',next_check_at=? WHERE tenant_id=? AND id=?",window.phase(),ts(window.deadline()),visitId,ts(visit),order==null?null:order.orderId(),ts(order==null?null:order.effectiveAt()),ts(now),ts(now.plusSeconds(60)),c.tenant(),c.id());
        jdbc.update("UPDATE crm_store_summary SET phase=?,deadline_at=?,ownership_revision=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND store_id=?",window.phase(),ts(window.deadline()),c.revision()+1,ts(now),c.tenant(),c.storeId());
        if(window.expired(now)) {
            boolean pendingVisit=jdbc.queryForObject("SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND ownership_id=? AND status_code='SUBMITTED' AND checkin_accepted_at<? AND submitted_at<?",Long.class,c.tenant(),c.id(),ts(window.deadline()),ts(window.deadline()))>0;
            if(pendingVisit||facts.pendingAuthorization())return setHold(c,now,pendingVisit?"PENDING_REVIEW":"PENDING_ORDER");
            jdbc.update("UPDATE crm_store_ownership SET ended_at=?,end_reason=?,cooldown_until=?,hold_reason=NULL,hold_until=NULL WHERE tenant_id=? AND id=?",ts(now),window.expiryReason(),ts(now.plusSeconds(c.policy().cooldownHours()*3600L)),c.tenant(),c.id());
            jdbc.update("UPDATE crm_store_summary SET current_ownership_id=NULL,owner_employee_id=NULL,owner_employee_code=NULL,ownership_revision=NULL,phase=NULL,deadline_at=NULL WHERE tenant_id=? AND store_id=? AND current_ownership_id=?",c.tenant(),c.storeId(),c.id());
            event(c,"AUTO_RELEASED",window.expiryReason(),now);
            return new RefreshResult(c.id(),"RELEASED",window.phase(),window.deadline(),null);
        }
        jdbc.update("UPDATE crm_store_ownership SET hold_reason=NULL,hold_until=NULL WHERE tenant_id=? AND id=?",c.tenant(),c.id());
        if(!c.deadline().equals(window.deadline()))event(c,"PROTECTION_UPDATED",window.phase(),now);
        return new RefreshResult(c.id(),"PROTECTED",window.phase(),window.deadline(),null);
    }
    private void applyCaptures(Cycle c,com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Facts facts,Instant now){
        if(facts==null)return;
        if(!c.tenant().equals(facts.tenantId())||!c.storeId().equals(facts.storeId())||!c.id().equals(facts.ownershipId()))throw new IllegalArgumentException("采集事实范围错误");
        boolean changed=false;
        for(var f:facts.items()){
            if(f.submittedAt()==null||f.checkedOutAt()==null)continue;
            String status="EFFECTIVE".equals(f.decision())?"EFFECTIVE":"SUBMITTED";
            int n=jdbc.update("UPDATE crm_visit SET status_code=?,qualified_at=IF(?='EFFECTIVE',checkin_accepted_at,NULL),checked_out_at=?,review_source='AUTOMATIC',reviewed_at=?,review_reason=?,capture_revision=?,revision=revision+1,updated_at=?,updated_by='SALES_EVIDENCE' WHERE tenant_id=? AND id=? AND store_id=? AND ownership_id=? AND capture_ref=? AND employee_code_snapshot=? AND validation_policy_version=? AND checkin_accepted_at=? AND submitted_at IS NOT NULL AND status_code IN ('SUBMITTED','EFFECTIVE') AND (review_source IS NULL OR review_source='AUTOMATIC') AND (capture_revision IS NULL OR capture_revision<?)",status,status,ts(f.checkedOutAt()),ts(now),f.reason(),f.revision(),ts(now),c.tenant(),f.visitId(),c.storeId(),c.id(),f.captureRef(),f.employeeCode(),f.policyId(),ts(f.checkedInAt()),f.revision());
            if(n>0){changed=true;jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,ownership_id,object_type,object_id,event_type,actor_user_id,occurred_at,reason,request_id,event_seq) VALUES(?,?,?,?,'VISIT',?,?,'SALES_EVIDENCE',?,?,?,1)",IdWorker.getId(),c.tenant(),c.storeId(),c.id(),f.visitId(),"VISIT_"+status,ts(now),f.reason(),java.util.UUID.randomUUID().toString());}
        }
        if(changed)jdbc.update("UPDATE crm_store_summary SET valid_visit_count=(SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),first_valid_visit_at=(SELECT MIN(visited_at) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),last_visit_at=(SELECT MAX(visited_at) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),visit_synced_at=? WHERE tenant_id=? AND store_id=?",c.tenant(),c.storeId(),c.tenant(),c.storeId(),c.tenant(),c.storeId(),ts(now),c.tenant(),c.storeId());
    }
    private void event(Cycle c,String type,String reason,Instant now) {
        jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,ownership_id,object_type,object_id,event_type,actor_user_id,occurred_at,reason,request_id,event_seq) VALUES(?,?,?,?,'OWNERSHIP',?,?,'CRM_LIFECYCLE',?,?,?,1)",IdWorker.getId(),c.tenant(),c.storeId(),c.id(),c.id(),type,ts(now),reason,java.util.UUID.randomUUID().toString());
    }
    private static Instant instant(Object t){return t==null?null:t instanceof Timestamp stamp?stamp.toInstant():((java.time.LocalDateTime)t).toInstant(java.time.ZoneOffset.UTC);}
    private static Timestamp ts(Instant i) { return i==null?null:Timestamp.from(i); }
}
