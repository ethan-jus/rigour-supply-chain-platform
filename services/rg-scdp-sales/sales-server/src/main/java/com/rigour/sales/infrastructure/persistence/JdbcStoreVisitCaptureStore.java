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
        visits.insertVisit(capture,tenant,day,null,profile,"CRM_STORE",null,null,UUID.fromString(c.policyId()),c.checkedInAt());
        jdbc.update("UPDATE sales_visit SET crm_store_id=?,crm_visit_id=?,crm_ownership_id=?,crm_employee_code=? WHERE tenant_id=? AND id=?",c.storeId(),c.visitId(),c.ownershipId(),c.employeeCode(),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        visits.insertTargetSnapshot(UUID.randomUUID(),tenant,capture,"CRM_STORE",null,null,null,c.storeName(),c.address(),c.longitude(),c.latitude(),profile,c.checkedInAt());
        visits.insertCheckpoint(UUID.randomUUID(),tenant,capture,"CHECK_IN","crm:"+c.visitId(),c.checkedInAt(),c.checkedInAt(),location.longitude(),location.latitude(),location.accuracyMeters(),c.distanceMeters(),"RECEIVED");
    }
    @Transactional(readOnly=true) public Facts facts(UUID tenant,String store,String ownership){
        var items=jdbc.query("SELECT v.crm_visit_id,BIN_TO_UUID(v.id) capture_id,v.crm_employee_code,BIN_TO_UUID(v.visit_policy_version_id) policy_id,v.checked_in_at,v.checked_out_at,v.result_submitted_at,r.decision,r.review_note,COALESCE(r.version,-1)+1 capture_revision FROM sales_visit v LEFT JOIN sales_visit_review r ON r.tenant_id=v.tenant_id AND r.visit_id=v.id AND r.review_type='AUTOMATIC_ASSESSMENT' WHERE v.tenant_id=? AND v.crm_store_id=? AND v.crm_ownership_id=? ORDER BY v.checked_in_at,v.id",(r,n)->new Outcome(r.getString("crm_visit_id"),r.getString("capture_id"),r.getString("crm_employee_code"),r.getString("policy_id"),r.getTimestamp("checked_in_at").toInstant(),r.getTimestamp("checked_out_at")==null?null:r.getTimestamp("checked_out_at").toInstant(),r.getTimestamp("result_submitted_at")==null?null:r.getTimestamp("result_submitted_at").toInstant(),"EFFECTIVE".equals(r.getString("decision"))?"EFFECTIVE":"PENDING_REVIEW",r.getString("review_note")==null?"采集证据尚未达标":r.getString("review_note"),r.getLong("capture_revision")),SalesUuidCodec.encode(tenant),store,ownership);
        return new Facts(tenant.toString(),store,ownership,clock.instant(),items);
    }
}
