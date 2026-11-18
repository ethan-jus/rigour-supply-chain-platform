package com.rigour.sales.infrastructure.persistence;
import com.rigour.sales.application.port.out.*;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
import com.rigour.shared.core.exception.StateConflictException;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class JdbcStoreVisitCaptureStore implements StoreVisitCaptureStore {
    private final JdbcTemplate jdbc;private final SalesWorkVisitRepository visits;private final Clock clock;
    public JdbcStoreVisitCaptureStore(JdbcTemplate jdbc,SalesWorkVisitRepository visits,Clock clock){this.jdbc=jdbc;this.visits=visits;this.clock=clock;}
    public boolean cancel(UUID tenant,UUID capture,String reason,java.time.Instant now){
        int updated=jdbc.update("UPDATE sales_visit SET status='CANCELLED',cancelled_at=?,cancellation_reason=?,checked_out_at=COALESCE(checked_out_at,?),finalized_at=?,final_reason_code='USER_CANCELLED',version=version+1,updated_at=? WHERE tenant_id=? AND id=? AND crm_visit_id IS NOT NULL AND result_submitted_at IS NULL AND status IN ('CHECKED_IN','CHECKED_OUT')",
                java.sql.Timestamp.from(now),reason,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        if(updated==1){
            jdbc.update("UPDATE sales_visit_plan p JOIN sales_visit v ON v.tenant_id=p.tenant_id AND v.visit_plan_id=p.id SET p.status='PLANNED',p.version=p.version+1,p.updated_at=? WHERE v.tenant_id=? AND v.id=? AND p.status IN ('IN_PROGRESS','COMPLETED')",java.sql.Timestamp.from(now),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
            jdbc.update("UPDATE sales_visit SET visit_plan_id=NULL WHERE tenant_id=? AND id=?",SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        }
        return updated==1;
    }
    public Link link(UUID tenant,UUID capture){return jdbc.query("SELECT crm_store_id,crm_visit_id,crm_ownership_id FROM sales_visit WHERE tenant_id=? AND id=? AND crm_visit_id IS NOT NULL",(r,n)->new Link(r.getString(1),r.getString(2),r.getString(3)),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture)).stream().findFirst().orElse(null);}
    public com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Accepted fieldwork(UUID tenant,UUID capture,boolean lock){
        var values=jdbc.query("SELECT crm_fieldwork_json FROM sales_visit WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),(rs,n)->rs.getString(1),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        return values.isEmpty()||values.getFirst()==null?null:new tools.jackson.databind.ObjectMapper().readValue(values.getFirst(),com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Accepted.class);
    }
    public void fieldwork(UUID tenant,UUID capture,com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Accepted accepted){
        jdbc.update("UPDATE sales_visit SET crm_fieldwork_json=? WHERE tenant_id=? AND id=?",new tools.jackson.databind.ObjectMapper().writeValueAsString(accepted),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        var draft=accepted.draft();
        if(draft.nextContactDate()!=null){
            var now=java.sql.Timestamp.from(clock.instant());
            jdbc.update("""
                INSERT INTO sales_visit_plan(id,tenant_id,sales_profile_id,planned_date,target_type,
                  objective,status,created_by,version,created_at,updated_at,source_capture_id,crm_store_id,action_code,target_snapshot_json)
                SELECT ?,v.tenant_id,v.sales_profile_id,?,'CRM_STORE',?,'PLANNED',?,0,?,?,v.id,v.crm_store_id,?,
                  JSON_OBJECT('name',t.store_name,'address',t.store_address,'longitude',t.store_longitude,'latitude',t.store_latitude)
                FROM sales_visit v JOIN sales_visit_target_snapshot t ON t.tenant_id=v.tenant_id AND t.visit_id=v.id
                WHERE v.tenant_id=? AND v.id=?
                """,SalesUuidCodec.encode(UUID.randomUUID()),java.sql.Date.valueOf(draft.nextContactDate()),
                accepted.labels().get("VISIT_NEXT_ACTION:"+draft.nextAction()),SalesUuidCodec.encode(UUID.fromString(accepted.actorUserId())),now,now,
                draft.nextAction(),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        }
    }
    @Transactional public void create(UUID tenant,UUID profile,UUID day,UUID capture,Context c,LocationEvidence location,UUID visitPlanId){
        visits.lockSalesProfile(tenant,profile);
        var existing=link(tenant,capture);if(existing!=null){if(!existing.visitId().equals(c.visitId()))throw new StateConflictException("采集请求已被占用");return;}
        if(visits.findActiveVisit(tenant,profile).isPresent())throw new StateConflictException("请先结束当前进行中的拜访");
        if(visitPlanId!=null){
            var plans=jdbc.queryForList("SELECT id FROM sales_visit_plan WHERE tenant_id=? AND sales_profile_id=? AND id=? AND crm_store_id=? AND action_code='VISIT' AND status='PLANNED' FOR UPDATE",SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(profile),SalesUuidCodec.encode(visitPlanId),c.storeId());
            if(plans.isEmpty())throw new StateConflictException("到店计划不存在或已开始");
            jdbc.update("UPDATE sales_visit_plan SET status='IN_PROGRESS',version=version+1,updated_at=? WHERE tenant_id=? AND id=?",java.sql.Timestamp.from(clock.instant()),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(visitPlanId));
        }
        ensurePolicySnapshot(tenant,c);
        visits.insertVisit(capture,tenant,day,visitPlanId,profile,"CRM_STORE",null,null,UUID.fromString(c.policyId()),c.checkedInAt());
        jdbc.update("UPDATE sales_visit SET crm_store_id=?,crm_visit_id=?,crm_ownership_id=?,crm_employee_code=? WHERE tenant_id=? AND id=?",c.storeId(),c.visitId(),c.ownershipId(),c.employeeCode(),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        visits.insertTargetSnapshot(UUID.randomUUID(),tenant,capture,"CRM_STORE",null,null,null,c.storeName(),c.address(),c.longitude(),c.latitude(),profile,c.checkedInAt());
        visits.insertCheckpoint(UUID.randomUUID(),tenant,capture,"CHECK_IN","crm:"+c.visitId(),c.checkedInAt(),c.checkedInAt(),location.longitude(),location.latitude(),location.accuracyMeters(),c.distanceMeters(),"RECEIVED");
    }
    private void ensurePolicySnapshot(UUID tenant,Context context) {
        var p=context.policy();
        if(p==null || !p.id().equals(context.policyId())) throw new StateConflictException("CRM 拜访政策快照缺失");
        byte[] tenantBytes=SalesUuidCodec.encode(tenant),id=SalesUuidCodec.encode(UUID.fromString(p.id()));
        var existing=jdbc.queryForList("SELECT id FROM sales_visit_policy_version WHERE tenant_id=? AND id=?",tenantBytes,id);
        if(!existing.isEmpty()) return;
        UUID parent=UUID.nameUUIDFromBytes((tenant+":crm-policy:"+p.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] parentBytes=SalesUuidCodec.encode(parent);
        var now=java.sql.Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO sales_visit_policy(id,tenant_id,policy_code,policy_name,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?,?) ON DUPLICATE KEY UPDATE id=id",
                parentBytes,tenantBytes,"CRM_"+p.id(),"CRM 拜访政策快照",now,now);
        jdbc.update("""
                INSERT INTO sales_visit_policy_version(id,tenant_id,policy_id,version_no,publish_status,
                require_assigned_target,allow_prospect_target,check_in_radius_meters,minimum_dwell_minutes,
                required_photo_count,recording_enabled,minimum_recording_seconds,maximum_clip_gap_seconds,
                ai_asr_enabled,ai_relevance_enabled,ai_duplicate_enabled,ai_auto_confirm_threshold,
                created_by,created_at,source_system,capture_rules_json)
                VALUES(?,?,?,1,'SNAPSHOT',1,0,?,?,?,?,?,?,?,?,?,?,?,?,'CRM',?)
                ON DUPLICATE KEY UPDATE id=id
                """,id,tenantBytes,parentBytes,p.radiusMeters(),p.minimumDwellMinutes(),p.requiredPhotoCount(),
                p.recordingEnabled(),p.minimumRecordingSeconds(),p.maximumClipGapSeconds(),p.aiAsrEnabled(),
                p.aiRelevanceEnabled(),p.aiDuplicateEnabled(),p.aiAutoConfirmThreshold(),parentBytes,now,
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(p.captureRules()));
    }
}
