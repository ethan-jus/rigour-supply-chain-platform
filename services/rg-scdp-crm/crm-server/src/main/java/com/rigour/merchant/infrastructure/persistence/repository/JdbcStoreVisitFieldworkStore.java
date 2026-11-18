package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.*;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence;
import com.rigour.merchant.application.port.out.StoreVisitFieldworkStore;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.core.exception.StateConflictException;
import com.rigour.shared.context.AuthorizationDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class JdbcStoreVisitFieldworkStore implements StoreVisitFieldworkStore {
    private final JdbcTemplate jdbc;private final JdbcStorePoolStore pools;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.rigour.shared.cache.QueryCache queryCache=com.rigour.shared.cache.QueryCache.disabled();
    public JdbcStoreVisitFieldworkStore(JdbcTemplate jdbc,JdbcStorePoolStore pools,ObjectMapper json){this.jdbc=jdbc;this.pools=pools;this.json=json;}
    public com.rigour.merchant.api.v1.model.StoreModels.Row current(StoreScope scope,String store){return pools.detail(scope,store);}
    public String businessType(String tenant,String store){return jdbc.queryForObject("SELECT business_type_code FROM crm_store_master WHERE tenant_id=? AND id=?",String.class,tenant,store);}
    public List<Progress> businesses(String tenant,String store){return jdbc.query("SELECT business_line_code,intention_level,cooperation_status FROM crm_store_business_progress WHERE tenant_id=? AND store_id=? ORDER BY business_line_code",(r,n)->new Progress(r.getString(1),r.getString(2),r.getString(3)),tenant,store);}
    public String syncStatus(String tenant,String visit){return jdbc.query("SELECT sync_status FROM crm_visit_fieldwork WHERE tenant_id=? AND visit_id=?",(r,n)->r.getString(1),tenant,visit).stream().findFirst().orElse("NONE");}
    public Draft submitted(String tenant,String visit){return jdbc.query("SELECT accepted_json FROM crm_visit_fieldwork WHERE tenant_id=? AND visit_id=?",(r,n)->json.readValue(r.getString(1),Accepted.class).draft(),tenant,visit).stream().findFirst().orElse(null);}
    public void validateCurrent(StoreScope scope,String store,String visit,Draft draft){
        var row=pools.detail(scope,store);
        if(!"PRIVATE".equals(row.poolType())||!Objects.equals(scope.access().employeeId(),row.ownerEmployeeId()))throw new AuthorizationDeniedException("store-current-owner");
        var v=jdbc.queryForList("SELECT ownership_id,salesperson_id,status_code FROM crm_visit WHERE tenant_id=? AND store_id=? AND id=?",scope.access().tenantId(),store,visit);
        if(v.isEmpty()||!Objects.equals(row.currentOwnershipId(),v.getFirst().get("ownership_id").toString())||!Objects.equals(scope.access().employeeId(),v.getFirst().get("salesperson_id").toString()))throw new StateConflictException("拜访归属已变化，请刷新");
        if(!"IN_PROGRESS".equals(v.getFirst().get("status_code")))throw new StateConflictException("拜访已提交，不能重复修改结果");
        if(row.revision()!=draft.storeRevision())throw new StateConflictException("门店资料已更新，请刷新后确认");
        if(!"ACTIVE".equals(row.recordStatus())||"CLOSED".equals(row.businessStatus()))throw new StateConflictException("当前门店状态不允许修改拜访资料");
    }
    @Transactional
    public void apply(Evidence e,Instant now){
        if(e.fieldwork()==null||e.submittedAt()==null)return;
        var a=e.fieldwork();var d=a.draft();String tenant=e.tenantId(),store=e.storeId();
        jdbc.queryForList("SELECT id FROM crm_store_master WHERE tenant_id=? AND id=? FOR UPDATE",tenant,store);
        if(!jdbc.queryForList("SELECT visit_id FROM crm_visit_fieldwork WHERE tenant_id=? AND visit_id=?",tenant,e.visitId()).isEmpty())return;
        var current=jdbc.queryForMap("SELECT s.record_status,s.business_status,s.business_status_reason,s.business_type_code,q.revision,q.current_ownership_id,q.owner_employee_id FROM crm_store_master s JOIN crm_store_summary q ON q.tenant_id=s.tenant_id AND q.store_id=s.id WHERE s.tenant_id=? AND s.id=?",tenant,store);
        boolean matches=Objects.equals(e.ownershipId(),Objects.toString(current.get("current_ownership_id"),null))
                &&Objects.equals(a.employeeId(),Objects.toString(current.get("owner_employee_id"),null))
                &&((Number)current.get("revision")).longValue()==d.storeRevision()
                &&"ACTIVE".equals(current.get("record_status"))&&!"CLOSED".equals(current.get("business_status"));
        // Always retain the historical observation. A conflict never overwrites newer store facts.
        jdbc.update("INSERT INTO crm_visit_fieldwork(tenant_id,visit_id,store_id,ownership_id,accepted_json,sync_status,submitted_at,applied_at) VALUES(?,?,?,?,?,?,?,?)",tenant,e.visitId(),store,e.ownershipId(),json.writeValueAsString(a),matches?"APPLIED":"CONFLICT",Timestamp.from(e.submittedAt()),matches?Timestamp.from(now):null);
        jdbc.update("DELETE FROM crm_visit_contact WHERE tenant_id=? AND visit_id=?",tenant,e.visitId());
        for(var c:a.contacts())jdbc.update("INSERT INTO crm_visit_contact(tenant_id,visit_id,contact_id,contact_snapshot,recorded_at,recorded_by) VALUES(?,?,?,?,?,?)",tenant,e.visitId(),c.id(),json.writeValueAsString(c),Timestamp.from(e.submittedAt()),a.actorUserId());
        if(matches){
            String before=Objects.toString(current.get("business_status"));
            if(!"UNKNOWN".equals(d.businessStatus())&&!before.equals(d.businessStatus()))
                pools.applyBusinessStatus(tenant,store,a.actorUserId(),a.employeeId(),"ACTIVE",before,Objects.toString(current.get("business_status_reason"),null),e.ownershipId(),d.businessStatus(),d.businessStatusReason()==null?"拜访现场核实":d.businessStatusReason(),"visit-profile-"+e.visitId(),1);
            if(!"UNKNOWN".equals(d.businessTypeCode()))jdbc.update("UPDATE crm_store_master SET business_type_code=? WHERE tenant_id=? AND id=?",d.businessTypeCode(),tenant,store);
            for(var b:d.businesses())jdbc.update("INSERT INTO crm_store_business_progress(tenant_id,store_id,business_line_code,intention_level,cooperation_status,source_visit_id,observed_at,actor_user_id) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE intention_level=VALUES(intention_level),cooperation_status=VALUES(cooperation_status),source_visit_id=VALUES(source_visit_id),observed_at=VALUES(observed_at),actor_user_id=VALUES(actor_user_id)",tenant,store,b.businessLineCode(),b.intentionLevel(),b.cooperationStatus(),e.visitId(),Timestamp.from(e.submittedAt()),a.actorUserId());
            jdbc.update("UPDATE crm_store_master SET revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND id=?",Timestamp.from(now),a.actorUserId(),tenant,store);
            jdbc.update("UPDATE crm_store_summary SET revision=revision+1,updated_at=? WHERE tenant_id=? AND store_id=?",Timestamp.from(now),tenant,store);
        }
        queryCache.changed(tenant);
        jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,ownership_id,object_type,object_id,event_type,actor_user_id,actor_employee_id,occurred_at,request_id,event_seq,reason,before_data,after_data) VALUES(?,?,?,?,'VISIT',?,'VISIT_FIELDWORK',?,?,?,?,2,?,?,?)",IdWorker.getId(),tenant,store,e.ownershipId(),e.visitId(),a.actorUserId(),a.employeeId(),Timestamp.from(now),"visit-profile-"+e.visitId(),matches?"拜访信息已同步":"门店资料变化，保留观察待核实",json.writeValueAsString(current),json.writeValueAsString(a));
    }
}
