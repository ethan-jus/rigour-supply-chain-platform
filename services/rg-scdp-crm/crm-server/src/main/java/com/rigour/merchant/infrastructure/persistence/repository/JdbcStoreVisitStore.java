package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.application.port.out.StoreVisitStore;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.merchant.domain.store.OwnershipProtection;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.StateConflictException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStoreVisitStore implements StoreVisitStore {
    private final JdbcTemplate jdbc;
    private final JdbcStorePoolStore pool;
    private final JdbcStoreLifecycleStore lifecycle;
    public JdbcStoreVisitStore(JdbcTemplate jdbc,JdbcStorePoolStore pool,JdbcStoreLifecycleStore lifecycle){this.jdbc=jdbc;this.pool=pool;this.lifecycle=lifecycle;}
    private String tenant(StoreScope s){return s.access().tenantId();}
    private void lock(StoreScope s,String store){jdbc.queryForList("SELECT id FROM crm_store_master WHERE tenant_id=? AND id=? FOR UPDATE",tenant(s),store);pool.detail(s,store);}
    private com.rigour.merchant.application.port.out.StoreLifecycleStore.Cycle owner(StoreScope s,String store,String ownership,Instant now){
        var cycle=lifecycle.current(tenant(s),store);
        if(cycle==null||!cycle.id().equals(ownership)||!cycle.employeeId().equals(s.access().employeeId()))throw new AuthorizationDeniedException("store-current-owner");
        if(!now.isBefore(cycle.deadline()))throw new StateConflictException("当前归属已到期，不能开始或补交新作业；系统正在核验回收");
        return cycle;
    }
    @Override @Transactional public Visit checkIn(StoreScope s,String store,CheckIn c,com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy policy,Instant now){
        lock(s,store);
        var previous=jdbc.queryForList("SELECT id,store_id,ownership_id,salesperson_id FROM crm_visit WHERE tenant_id=? AND request_id=?",tenant(s),c.requestId());
        if(!previous.isEmpty()){
            var p=previous.getFirst();
            if(!store.equals(p.get("store_id").toString())||!c.ownershipId().equals(p.get("ownership_id").toString())||!Objects.equals(s.access().employeeId(),p.get("salesperson_id").toString()))throw new StateConflictException("签到请求已用于其他拜访");
            return visit(s,store,p.get("id").toString());
        }
        var cycle=owner(s,store,c.ownershipId(),now);var row=pool.detail(s,store);
        if(!Objects.equals(row.coordinateSystem(),c.coordinateSystem()))throw new StateConflictException("定位坐标系与门店不同，请完成坐标转换后提交");
        if(row.longitude()==null||row.latitude()==null)throw new StateConflictException("请先补全门店定位");
        double distance=distance(c.longitude(),c.latitude(),row.longitude(),row.latitude());
        if(distance>policy.radiusMeters())throw new StateConflictException("当前位置超过门店"+policy.radiusMeters()+"米，不能签到");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND ownership_id=? AND status_code='IN_PROGRESS'",Long.class,tenant(s),cycle.id())>0)throw new StateConflictException("本轮已有未完成拜访，请先处理原记录");
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO crm_visit(id,tenant_id,created_at,updated_at,created_by,updated_by,store_id,ownership_id,salesperson_id,department_id_snapshot,visited_at,accepted_at,checkin_accepted_at,checkin_longitude,checkin_latitude,checkin_coordinate_system,accuracy_meters,distance_meters,store_snapshot,validation_policy_version,request_id,capture_ref,employee_code_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,JSON_OBJECT('name',?,'address',?,'longitude',?,'latitude',?,'coordinateSystem',?,'areaCode',?,'areaVersion',?),?,?,?,?)",id,tenant(s),ts(now),ts(now),s.access().userId(),s.access().userId(),store,cycle.id(),cycle.employeeId(),s.access().departmentId(),ts(now),ts(now),ts(now),c.longitude(),c.latitude(),c.coordinateSystem(),c.accuracyMeters(),BigDecimal.valueOf(distance),row.name(),row.address(),row.longitude(),row.latitude(),row.coordinateSystem(),row.areaCode(),s.catalog().version(),policy.id(),c.requestId(),c.captureRef(),s.access().employeeCode());
        bump(cycle,now);event(s,store,cycle.id(),id,"VISIT_CHECKED_IN",null,now);
        return visit(s,store,Long.toString(id));
    }
    @Override @Transactional public Visit submit(StoreScope s,String store,String id,SubmitVisit c,Instant now){
        lock(s,store);var v=visit(s,store,id);
        if(!Objects.equals(v.salespersonId(),s.access().employeeId()))throw new AuthorizationDeniedException("visit-owner");
        if(!"IN_PROGRESS".equals(v.status())) {
            if(Objects.equals(v.resultSummary(),c.resultSummary()))return v;
            throw new StateConflictException("拜访已提交，不能重复修改");
        }
        var cycle=owner(s,store,v.ownershipId(),now);
        if(v.revision()!=c.revision())throw new StateConflictException("拜访版本已变化");
        jdbc.update("UPDATE crm_visit SET status_code='SUBMITTED',result_summary=?,checked_out_at=?,submitted_at=?,revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND id=?",c.resultSummary(),ts(now),ts(now),ts(now),s.access().userId(),tenant(s),id);
        for(String ref:c.photoRefs())jdbc.update("INSERT INTO crm_visit_evidence(id,tenant_id,visit_id,file_ref,evidence_type,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,'PHOTO',?,?,?,?)",IdWorker.getId(),tenant(s),id,ref,ts(now),ts(now),s.access().userId(),s.access().userId());
        // 文件引用只是待核验材料，客户端不能通过填写字符串直接获得有效拜访。
        bump(cycle,now);event(s,store,cycle.id(),Long.parseLong(id),"VISIT_SUBMITTED",null,now);
        return visit(s,store,id);
    }
    @Override @Transactional public Visit cancel(StoreScope s,String store,String id,CancelVisit c,Instant now){
        lock(s,store);var v=visit(s,store,id);if(!Objects.equals(s.access().employeeId(),v.salespersonId()))throw new AuthorizationDeniedException("visit-owner");
        if("INVALID".equals(v.status())&&Objects.equals(v.reviewReason(),c.reason()))return v;
        if(!"IN_PROGRESS".equals(v.status())||c.revision()!=v.revision())throw new StateConflictException("仅能取消尚未提交的本次拜访");
        jdbc.update("UPDATE crm_visit SET status_code='INVALID',review_reason=?,review_source='CANCELLED',revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND id=?",c.reason(),ts(now),s.access().userId(),tenant(s),id);
        var cycle=lifecycle.current(tenant(s),store);if(cycle!=null&&cycle.id().equals(v.ownershipId()))bump(cycle,now);
        event(s,store,v.ownershipId(),Long.parseLong(id),"VISIT_CANCELLED",c.reason(),now);return visit(s,store,id);
    }
    @Override @Transactional public Visit review(StoreScope s,String store,String id,ReviewVisit c,Instant now){
        lock(s,store);var v=visit(s,store,id);
        if(v.revision()!=c.revision())throw new StateConflictException("拜访版本已变化");
        boolean revoke="VOID".equals(c.decision());
        if(revoke?!"EFFECTIVE".equals(v.status()):!"SUBMITTED".equals(v.status()))throw new StateConflictException("拜访状态不允许本次核验");
        jdbc.update("UPDATE crm_visit SET review_source='MANUAL',status_code=?,qualified_at=IF(?='EFFECTIVE',checkin_accepted_at,NULL),reviewed_at=?,reviewer_user_id=?,review_reason=?,revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND id=?",c.decision(),c.decision(),ts(now),s.access().userId(),c.reason(),ts(now),s.access().userId(),tenant(s),id);
        jdbc.update("UPDATE crm_store_summary SET valid_visit_count=(SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),first_valid_visit_at=(SELECT MIN(visited_at) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),last_visit_at=(SELECT MAX(visited_at) FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE'),visit_synced_at=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND store_id=?",tenant(s),store,tenant(s),store,tenant(s),store,ts(now),ts(now),tenant(s),store);
        var cycle=lifecycle.current(tenant(s),store);
        if(cycle!=null&&cycle.id().equals(v.ownershipId())) {
            var latest=jdbc.queryForList("SELECT id,qualified_at FROM crm_visit WHERE tenant_id=? AND ownership_id=? AND status_code='EFFECTIVE' ORDER BY qualified_at DESC,id DESC LIMIT 1",tenant(s),cycle.id());
            String visitId=latest.isEmpty()?null:latest.getFirst().get("id").toString();
            Instant visitAt=latest.isEmpty()?null:instant(latest.getFirst().get("qualified_at"));
            var orderAt=jdbc.queryForObject("SELECT last_valid_order_at FROM crm_store_ownership WHERE tenant_id=? AND id=?",Timestamp.class,tenant(s),cycle.id());
            var window=OwnershipProtection.calculate(cycle.policy(),cycle.startedAt(),visitAt,orderAt==null?null:orderAt.toInstant());
            jdbc.update("UPDATE crm_store_ownership SET phase=?,deadline_at=?,last_valid_visit_id=?,last_valid_visit_at=?,revision=revision+1,updated_at=?,next_check_at=? WHERE tenant_id=? AND id=?",window.phase(),ts(window.deadline()),visitId,ts(visitAt),ts(now),ts(now),tenant(s),cycle.id());
            jdbc.update("UPDATE crm_store_summary SET phase=?,deadline_at=?,ownership_revision=? WHERE tenant_id=? AND store_id=?",window.phase(),ts(window.deadline()),cycle.revision()+1,tenant(s),store);
        }
        event(s,store,v.ownershipId(),Long.parseLong(id),"VISIT_"+c.decision(),c.reason(),now);
        return visit(s,store,id);
    }
    @Override public List<Visit> visits(StoreScope s,String store){return queryVisits(s,store,null);}
    private List<Visit> queryVisits(StoreScope s,String store,String id){
        pool.detail(s,store);
        return jdbc.query("SELECT v.*,n.store_no,n.employee_no,n.ownership_no FROM crm_visit v LEFT JOIN (SELECT id,ROW_NUMBER() OVER(ORDER BY visited_at,id) store_no,ROW_NUMBER() OVER(PARTITION BY salesperson_id ORDER BY visited_at,id) employee_no,ROW_NUMBER() OVER(PARTITION BY ownership_id ORDER BY visited_at,id) ownership_no FROM crm_visit WHERE tenant_id=? AND store_id=? AND status_code='EFFECTIVE') n ON n.id=v.id WHERE v.tenant_id=? AND v.store_id=? AND (? IS NULL OR v.id=?) ORDER BY v.visited_at DESC,v.id DESC LIMIT 100",(r,n)->new Visit(r.getString("id"),r.getString("store_id"),r.getString("ownership_id"),r.getString("salesperson_id"),r.getString("status_code"),r.getTimestamp("visited_at").toInstant(),instant(r.getTimestamp("submitted_at")),instant(r.getTimestamp("qualified_at")),r.getString("result_summary"),r.getString("review_reason"),r.getLong("revision"),number(r.getObject("store_no")),number(r.getObject("employee_no")),number(r.getObject("ownership_no")),r.getObject("store_no")==null?null:r.getLong("store_no")==1?"INITIAL":"REVISIT",r.getString("capture_ref"),r.getString("review_source")),tenant(s),store,tenant(s),store,id,id);
    }
    private Visit visit(StoreScope s,String store,String id){return queryVisits(s,store,id).stream().filter(v->id.equals(v.id())).findFirst().orElseThrow(()->new StateConflictException("拜访不存在"));}
    @Override @Transactional public OrderPermit authorize(StoreScope s,String store,OrderAuthorization c,Instant now){
        lock(s,store);
        var prior=jdbc.queryForList("SELECT actor_user_id,store_id,ownership_id,object_id,after_data FROM crm_store_event WHERE tenant_id=? AND request_id=? AND event_type='ORDER_AUTHORIZED'",tenant(s),c.requestId());
        if(!prior.isEmpty()){
            var p=prior.getFirst();
            if(!store.equals(p.get("store_id").toString())||!c.ownershipId().equals(p.get("ownership_id").toString())||!c.orderId().equals(p.get("object_id").toString())||!s.access().userId().equals(p.get("actor_user_id")))throw new StateConflictException("订单授权请求不匹配");
            return new tools.jackson.databind.ObjectMapper().readValue(p.get("after_data").toString(),OrderPermit.class);
        }
        var cycle=owner(s,store,c.ownershipId(),now);var row=pool.detail(s,store);bump(cycle,now);
        var permit=new OrderPermit(store,cycle.id(),cycle.employeeId(),s.access().departmentId(),row.name(),cycle.revision()+1,now);
        jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,ownership_id,object_type,object_id,event_type,actor_user_id,actor_employee_id,occurred_at,request_id,event_seq,after_data) VALUES(?,?,?,?,'ORDER',?,'ORDER_AUTHORIZED',?,?,?,?,1,?)",IdWorker.getId(),tenant(s),store,cycle.id(),c.orderId(),s.access().userId(),s.access().employeeId(),ts(now),c.requestId(),new tools.jackson.databind.ObjectMapper().writeValueAsString(permit));
        return permit;
    }
    @Override public String evidenceCapture(StoreScope s,String store,String id){return visit(s,store,id).captureRef();}
    @Override public com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context captureContext(StoreScope s,String store,String id){
        var v=visit(s,store,id);
        if(!Objects.equals(s.access().employeeId(),v.salespersonId()))throw new AuthorizationDeniedException("visit-owner");
        var data=jdbc.queryForMap("SELECT capture_ref,employee_code_snapshot,validation_policy_version,store_snapshot,distance_meters FROM crm_visit WHERE tenant_id=? AND id=?",tenant(s),id);
        var snapshot=new tools.jackson.databind.ObjectMapper().readTree(data.get("store_snapshot").toString());
        return new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context(id,store,v.ownershipId(),(String)data.get("employee_code_snapshot"),(String)data.get("capture_ref"),(String)data.get("validation_policy_version"),v.visitedAt(),snapshot.get("name").asText(),snapshot.get("address").asText(),new BigDecimal(snapshot.get("longitude").asText()),new BigDecimal(snapshot.get("latitude").asText()),snapshot.get("coordinateSystem").asText(),(BigDecimal)data.get("distance_meters"),v.revision(),v.status());
    }
    private void bump(com.rigour.merchant.application.port.out.StoreLifecycleStore.Cycle c,Instant now){
        jdbc.update("UPDATE crm_store_ownership SET revision=revision+1,updated_at=?,next_check_at=? WHERE tenant_id=? AND id=?",ts(now),ts(now),c.tenant(),c.id());
        jdbc.update("UPDATE crm_store_summary SET ownership_revision=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND store_id=?",c.revision()+1,ts(now),c.tenant(),c.storeId());
    }
    private void event(StoreScope s,String store,String ownership,long object,String type,String reason,Instant now){jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,ownership_id,object_type,object_id,event_type,actor_user_id,actor_employee_id,occurred_at,request_id,event_seq,reason) VALUES(?,?,?,?,'VISIT',?,?,?,?,?,?,1,?)",IdWorker.getId(),tenant(s),store,ownership,object,type,s.access().userId(),s.access().employeeId(),ts(now),UUID.randomUUID().toString(),reason);}
    private static double distance(BigDecimal lng,BigDecimal lat,BigDecimal slng,BigDecimal slat){double a=Math.sin(Math.toRadians(lat.subtract(slat).doubleValue())/2),b=Math.sin(Math.toRadians(lng.subtract(slng).doubleValue())/2);return 6371000*2*Math.asin(Math.min(1,Math.sqrt(a*a+Math.cos(Math.toRadians(lat.doubleValue()))*Math.cos(Math.toRadians(slat.doubleValue()))*b*b)));}
    private static Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);}
    private static Long number(Object v){return v==null?null:((Number)v).longValue();}
    private static Instant instant(Object t){return t==null?null:t instanceof Timestamp stamp?stamp.toInstant():((java.time.LocalDateTime)t).toInstant(java.time.ZoneOffset.UTC);}
}
