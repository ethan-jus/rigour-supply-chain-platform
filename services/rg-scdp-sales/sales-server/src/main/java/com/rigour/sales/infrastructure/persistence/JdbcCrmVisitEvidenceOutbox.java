package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.CrmVisitEvidenceOutbox;
import com.rigour.sales.application.port.out.SalesWorkManagementRepository;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcCrmVisitEvidenceOutbox implements CrmVisitEvidenceOutbox {
    private final JdbcTemplate jdbc;
    private final SalesWorkManagementRepository facts;
    private final ObjectMapper json;
    private final Clock clock;
    public JdbcCrmVisitEvidenceOutbox(JdbcTemplate jdbc,SalesWorkManagementRepository facts,ObjectMapper json,Clock clock) {
        this.jdbc=jdbc; this.facts=facts; this.json=json; this.clock=clock;
    }
    @Override @Transactional
    public void enqueue(UUID tenant,UUID capture) {
        var rows=jdbc.queryForList("SELECT * FROM sales_visit WHERE tenant_id=? AND id=? AND crm_visit_id IS NOT NULL FOR UPDATE",SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        if(rows.isEmpty()) return;
        var v=rows.getFirst();
        var f=facts.findVisitAssessment(tenant,capture).orElseThrow(()->new IllegalStateException("CRM 采集证据缺少政策快照"));
        long revision=((Number)v.get("crm_capture_revision")).longValue()+1;
        Instant now=clock.instant();
        String outcome=(String)v.get("contact_outcome");
        String summary=("CONTACTED".equals(outcome)?"已接触负责人":"未接触负责人："+outcome)
                +(v.get("kp_name")==null?"":"；联系人："+v.get("kp_name"))
                +(v.get("kp_phone")==null?"":"；电话："+v.get("kp_phone"))
                +(v.get("intention_level")==null?"":"；意向："+v.get("intention_level"))
                +(v.get("result_note")==null?"":"；说明："+v.get("result_note"));
        var payload=new Evidence(tenant.toString(),v.get("crm_store_id").toString(),v.get("crm_ownership_id").toString(),
                v.get("crm_visit_id").toString(),capture.toString(),(String)v.get("crm_employee_code"),
                SalesUuidCodec.decode((byte[])v.get("visit_policy_version_id")).toString(),revision,now,
                f.checkedInAt(),f.checkedOutAt(),f.resultSubmittedAt(),outcome,summary,
                f.verifiedStorefrontPhotoCount(),f.verifiedRecordingDurationMs(),f.recordingEvidenceStatus(),
                f.aiRecommendation(),f.aiConfidenceScore(),f.aiResultAdopted());
        jdbc.update("UPDATE sales_visit SET crm_capture_revision=?,crm_last_enqueued_at=? WHERE tenant_id=? AND id=?",
                revision,Timestamp.from(now),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        jdbc.update("""
                INSERT INTO sales_outbox_event(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,
                event_version,payload_json,occurred_at,publish_status,retry_count,created_at)
                VALUES(?,?,'CRM_VISIT_CAPTURE',?,?,'CrmVisitEvidenceChanged',1,?,?,'PENDING',0,?)
                """,SalesUuidCodec.encode(UUID.randomUUID()),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture),
                revision,json.writeValueAsString(payload),Timestamp.from(now),Timestamp.from(now));
    }
}
