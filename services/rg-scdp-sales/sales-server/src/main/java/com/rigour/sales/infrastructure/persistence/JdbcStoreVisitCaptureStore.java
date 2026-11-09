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
    public Link link(UUID tenant,UUID capture){return jdbc.query("SELECT crm_store_id,crm_visit_id,crm_ownership_id FROM sales_visit WHERE tenant_id=? AND id=? AND crm_visit_id IS NOT NULL",(r,n)->new Link(r.getString(1),r.getString(2),r.getString(3)),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture)).stream().findFirst().orElse(null);}
    @Transactional public void create(UUID tenant,UUID profile,UUID day,UUID capture,Context c,LocationEvidence location){
        visits.lockSalesProfile(tenant,profile);
        var existing=link(tenant,capture);if(existing!=null){if(!existing.visitId().equals(c.visitId()))throw new StateConflictException("采集请求已被占用");return;}
        if(visits.findActiveVisit(tenant,profile).isPresent())throw new StateConflictException("请先结束当前进行中的拜访");
        ensurePolicySnapshot(tenant,c);
        visits.insertVisit(capture,tenant,day,null,profile,"CRM_STORE",null,null,UUID.fromString(c.policyId()),c.checkedInAt());
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
                created_by,created_at,source_system)
                VALUES(?,?,?,1,'SNAPSHOT',1,0,?,?,?,?,?,?,?,?,?,?,?,?,'CRM')
                ON DUPLICATE KEY UPDATE id=id
                """,id,tenantBytes,parentBytes,p.radiusMeters(),p.minimumDwellMinutes(),p.requiredPhotoCount(),
                p.recordingEnabled(),p.minimumRecordingSeconds(),p.maximumClipGapSeconds(),p.aiAsrEnabled(),
                p.aiRelevanceEnabled(),p.aiDuplicateEnabled(),p.aiAutoConfirmThreshold(),parentBytes,now);
    }
}
