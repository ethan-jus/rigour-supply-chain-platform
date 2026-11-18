package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.CrmVisitEvidenceOutbox;
import com.rigour.sales.application.port.out.SalesWorkManagementRepository;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence;
import com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Accepted;
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
        var fieldwork=v.get("crm_fieldwork_json")==null?null:json.readValue(v.get("crm_fieldwork_json").toString(),Accepted.class);
        if(fieldwork!=null)summary=fieldworkSummary(fieldwork,(String)v.get("result_note"));
        var payload=new Evidence(tenant.toString(),v.get("crm_store_id").toString(),v.get("crm_ownership_id").toString(),
                v.get("crm_visit_id").toString(),capture.toString(),(String)v.get("crm_employee_code"),
                SalesUuidCodec.decode((byte[])v.get("visit_policy_version_id")).toString(),revision,now,
                f.checkedInAt(),f.checkedOutAt(),f.resultSubmittedAt(),outcome,summary,
                f.verifiedStorefrontPhotoCount(),f.verifiedRecordingDurationMs(),f.recordingEvidenceStatus(),
                f.aiRecommendation(),f.aiConfidenceScore(),f.aiResultAdopted(),fieldwork,
                v.get("cancelled_at")==null?null:jdbc.queryForObject("SELECT cancelled_at FROM sales_visit WHERE tenant_id=? AND id=?",Timestamp.class,SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture)).toInstant(),(String)v.get("cancellation_reason"),transcriptionStatus(tenant,capture),"WORK_DAY_CUTOFF".equals(v.get("final_reason_code"))?"WORK_DAY_CUTOFF":null);
        jdbc.update("UPDATE sales_visit SET crm_capture_revision=?,crm_last_enqueued_at=? WHERE tenant_id=? AND id=?",
                revision,Timestamp.from(now),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture));
        jdbc.update("""
                INSERT INTO sales_outbox_event(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,
                event_version,payload_json,occurred_at,publish_status,retry_count,created_at)
                VALUES(?,?,'CRM_VISIT_CAPTURE',?,?,'CrmVisitEvidenceChanged',1,?,?,'PENDING',0,?)
                """,SalesUuidCodec.encode(UUID.randomUUID()),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(capture),
                revision,json.writeValueAsString(payload),Timestamp.from(now),Timestamp.from(now));
    }
    private String transcriptionStatus(UUID tenant,UUID visit) {
        return jdbc.queryForObject("""
                SELECT CASE WHEN COUNT(*)=0 THEN 'MISSING'
                    WHEN SUM(a.status='FAILED')>0 THEN 'FAILED'
                    WHEN SUM(a.status IS NULL OR a.status<>'SUCCEEDED')>0 THEN 'PROCESSING'
                    ELSE 'SUCCEEDED' END FROM sales_recording_clip c
                JOIN sales_recording_session r ON r.tenant_id=c.tenant_id AND r.id=c.recording_session_id
                LEFT JOIN sales_recording_analysis a ON a.tenant_id=c.tenant_id AND a.clip_id=c.id
                WHERE c.tenant_id=? AND r.visit_id=?
                """,String.class,SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(visit));
    }
    private static String label(Accepted value,String dictionary,String code) {
        return value.labels().getOrDefault(dictionary+":"+code,code==null?"":code);
    }
    private static String fieldworkSummary(Accepted value,String note) {
        var d=value.draft();var parts=new java.util.ArrayList<String>();
        parts.add(label(value,"VISIT_CONTACT_OUTCOME",d.contactOutcome()));
        parts.add(label(value,"CRM_STORE_BUSINESS_STATUS",d.businessStatus()));
        parts.add(label(value,"STORE_BUSINESS_TYPE",d.businessTypeCode()));
        if(!value.contacts().isEmpty())parts.add("接待人："+value.contacts().stream().map(c->c.name()+"（"+c.role()+"）").collect(java.util.stream.Collectors.joining("、")));
        if(d.noIntention())parts.add("暂无合作意向");
        for(var b:d.businesses())parts.add(label(value,"BUSINESS_LINE",b.businessLineCode())+"："+
                (b.cooperationStatus()!=null&&!b.cooperationStatus().isBlank()?label(value,"CUSTOMER_COOP_STATUS",b.cooperationStatus()):label(value,"CUSTOMER_INTENTION_LEVEL",b.intentionLevel())));
        if(d.noContactReason()!=null&&!d.noContactReason().isBlank())parts.add(label(value,"VISIT_NO_CONTACT_REASON",d.noContactReason()));
        if(note!=null&&!note.isBlank())parts.add(note);
        String summary=String.join("；",parts);
        // The structured snapshot remains complete; the existing CRM summary is VARCHAR(2000).
        return summary.codePointCount(0,summary.length())<=2000?summary:summary.substring(0,summary.offsetByCodePoints(0,1999))+"…";
    }
}
