package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesAudioAiClient.TranscriptSegment;
import com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository;
import com.rigour.sales.application.port.out.SalesSpeechTranscriber;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSalesRecordingAnalysisRepository implements SalesRecordingAnalysisRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.rigour.sales.application.port.out.CrmVisitEvidenceOutbox evidence;

    public JdbcSalesRecordingAnalysisRepository(JdbcTemplate jdbc, ObjectMapper json, com.rigour.sales.application.port.out.CrmVisitEvidenceOutbox evidence) {
        this.jdbc = jdbc;
        this.json = json;
        this.evidence = evidence;
    }

    @Override
    public void enqueue(UUID tenantId, UUID visitId, UUID clipId, Instant now) {
        jdbc.update("""
                INSERT INTO sales_visit_speaker_context (tenant_id,visit_id,profiles_json,updated_at)
                VALUES (?,?,'[]',?) ON DUPLICATE KEY UPDATE visit_id=visit_id
                """,bin(tenantId),bin(visitId),ts(now));
        jdbc.update("""
                INSERT INTO sales_recording_analysis (tenant_id,clip_id,visit_id,next_attempt_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE clip_id=clip_id
                """, bin(tenantId), bin(clipId), bin(visitId), ts(now), ts(now), ts(now));
    }

    @Override
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Optional<Job> claim(Instant now, UUID leaseId, Instant leaseUntil) {
        // Row lease persists across instances; no transaction stays open during the supplier call.
        var jobs = jdbc.query("""
                SELECT a.tenant_id,a.visit_id,a.clip_id,c.object_key,a.attempts
                FROM sales_recording_analysis a JOIN sales_recording_clip c ON c.tenant_id=a.tenant_id AND c.id=a.clip_id
                JOIN sales_visit v ON v.tenant_id=a.tenant_id AND v.id=a.visit_id
                WHERE a.status IN ('PENDING','PROCESSING') AND v.status='CHECKED_OUT'
                  AND a.next_attempt_at<=? AND (a.lease_until IS NULL OR a.lease_until<?)
                  AND NOT EXISTS (SELECT 1 FROM sales_recording_analysis earlier
                    JOIN sales_recording_clip ec ON ec.tenant_id=earlier.tenant_id AND ec.id=earlier.clip_id
                    WHERE earlier.tenant_id=a.tenant_id AND earlier.visit_id=a.visit_id
                    AND earlier.status IN ('PENDING','PROCESSING') AND ec.clip_index<c.clip_index)
                ORDER BY a.next_attempt_at,a.created_at,c.clip_index LIMIT 1
                """, (r,n) -> new Job(uuid(r.getBytes(1)),uuid(r.getBytes(2)),uuid(r.getBytes(3)),leaseId,
                r.getString(4),r.getInt(5),0,List.of()),ts(now),ts(now));
        if (jobs.isEmpty()) return Optional.empty();
        var candidate = jobs.getFirst();
        // Serialize one visit across workers while keeping model inference outside a transaction.
        var states=jdbc.queryForList("SELECT version,profiles_json FROM sales_visit_speaker_context WHERE tenant_id=? AND visit_id=? FOR UPDATE SKIP LOCKED",
                bin(candidate.tenantId()),bin(candidate.visitId()));
        if(states.isEmpty())return Optional.empty();
        var state=states.getFirst();
        Integer active=jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_recording_analysis WHERE tenant_id=? AND visit_id=? AND clip_id<>?
                AND status='PROCESSING' AND lease_until>=?
                """,Integer.class,bin(candidate.tenantId()),bin(candidate.visitId()),bin(candidate.clipId()),ts(now));
        if(active!=null && active>0)return Optional.empty();
        var job=new Job(candidate.tenantId(),candidate.visitId(),candidate.clipId(),leaseId,candidate.objectKey(),candidate.attempts(),
                ((Number)state.get("version")).longValue(),List.of(json.readValue(state.get("profiles_json").toString(),SalesSpeechTranscriber.SpeakerProfile[].class)));
        int claimed=jdbc.update("""
                UPDATE sales_recording_analysis SET status='PROCESSING',lease_id=?,lease_until=?
                WHERE tenant_id=? AND clip_id=? AND status IN ('PENDING','PROCESSING') AND next_attempt_at<=?
                AND (lease_until IS NULL OR lease_until<?)
                """,bin(leaseId),ts(leaseUntil),bin(job.tenantId()),bin(job.clipId()),ts(now),ts(now));
        return claimed==1?Optional.of(job):Optional.empty();
    }

    private static final String OWN_LEASE = " WHERE tenant_id=? AND clip_id=? AND lease_id=?";

    @Override
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void transcribed(Job job, SalesSpeechTranscriber.Result result, Instant now) {
        Long version=jdbc.queryForObject("SELECT version FROM sales_visit_speaker_context WHERE tenant_id=? AND visit_id=? FOR UPDATE",
                Long.class,bin(job.tenantId()),bin(job.visitId()));
        if(version==null || version!=job.speakerVersion()) {
            jdbc.update("UPDATE sales_recording_analysis SET status='PENDING',lease_id=NULL,lease_until=NULL,next_attempt_at=?"+OWN_LEASE,
                    ts(now),bin(job.tenantId()),bin(job.clipId()),bin(job.leaseId()));
            return;
        }
        int saved=jdbc.update("""
                UPDATE sales_recording_analysis SET status='SUCCEEDED',transcript=?,segments_json=?,provider_request_id=?,
                asr_model=?,summary_status='NOT_REQUESTED',attempts=0,error_code=NULL,lease_id=NULL,lease_until=NULL,updated_at=?
                """+OWN_LEASE,result.transcript(),json.writeValueAsString(result.segments()),result.requestId(),result.model(),ts(now),bin(job.tenantId()),bin(job.clipId()),bin(job.leaseId()));
        if(saved==1) {
            jdbc.update("UPDATE sales_visit_speaker_context SET voice_registry_version=NULL,version=version+1,profiles_json=?,model=?,updated_at=? WHERE tenant_id=? AND visit_id=?",
                    json.writeValueAsString(result.speakerProfiles()),result.speakerModel(),ts(now),bin(job.tenantId()),bin(job.visitId()));
            evidence.enqueue(job.tenantId(),job.visitId());
        }
    }

    @Override
    @Transactional
    public void deferred(Job job, String errorCode, boolean terminal, Instant now) {
        boolean failed=terminal;
        int changed=jdbc.update("""
                UPDATE sales_recording_analysis SET status=?,error_code=?,attempts=LEAST(attempts+1,30),
                lease_id=NULL,lease_until=NULL,next_attempt_at=?,updated_at=?
                """+OWN_LEASE,failed?"FAILED":"PENDING",errorCode,
                ts(now.plusSeconds(Math.min(1800,30L<<Math.min(job.attempts(),5)))),ts(now),bin(job.tenantId()),bin(job.clipId()),bin(job.leaseId()));
        if(changed==1 && failed)evidence.enqueue(job.tenantId(),job.visitId());
    }

    @Override
    public List<Analysis> list(UUID tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT a.*,c.clip_index FROM sales_recording_analysis a
                JOIN sales_recording_clip c ON c.tenant_id=a.tenant_id AND c.id=a.clip_id
                WHERE a.tenant_id=? AND a.visit_id=? ORDER BY c.clip_index
                """, (r,n) -> new Analysis(uuid(r.getBytes("clip_id")),r.getInt("clip_index"),r.getString("status"),
                r.getString("transcript"),segments(r.getString("segments_json")),r.getString("summary_status"),r.getString("summary"),
                r.getString("summary_model"),r.getString("error_code"),r.getTimestamp("updated_at").toInstant()),bin(tenantId),bin(visitId));
    }

    @Override
    public int retry(UUID tenantId, UUID visitId, UUID clipId, Instant now) {
        return jdbc.update("""
                UPDATE sales_recording_analysis SET status='PENDING',attempts=0,error_code=NULL,next_attempt_at=?,updated_at=?
                WHERE tenant_id=? AND visit_id=? AND clip_id=? AND status='FAILED'
                  AND (lease_until IS NULL OR lease_until<?)
                """,ts(now),ts(now),bin(tenantId),bin(visitId),bin(clipId),ts(now));
    }

    @Override
    public List<com.rigour.sales.api.v1.model.SalesRecordingAnalysisModels.Speaker> speakers(UUID tenant,UUID visit) {
        var rows=jdbc.queryForList("SELECT JSON_LENGTH(profiles_json) total,speaker_names_json FROM sales_visit_speaker_context WHERE tenant_id=? AND visit_id=?",bin(tenant),bin(visit));
        if(rows.isEmpty())return List.of();
        var row=rows.getFirst();int count=((Number)row.get("total")).intValue();
        var names=row.get("speaker_names_json")==null?json.createObjectNode():json.readTree(row.get("speaker_names_json").toString());
        var result=new java.util.ArrayList<com.rigour.sales.api.v1.model.SalesRecordingAnalysisModels.Speaker>();
        for(int id=0;id<count;id++) {
            String label="";for(int n=id+1;n>0;n=(n-1)/26)label=(char)('A'+(n-1)%26)+label;
            var name=names.get(Integer.toString(id));boolean matched=name!=null&&!name.isNull();
            result.add(new com.rigour.sales.api.v1.model.SalesRecordingAnalysisModels.Speaker(id,
                    matched?name.asText():label,matched?"SALESPERSON":"UNKNOWN"));
        }
        return List.copyOf(result);
    }

    private List<TranscriptSegment> segments(String value) {
        if (value == null) return List.of();
        return List.of(json.readValue(value, TranscriptSegment[].class));
    }
    private static byte[] bin(UUID value) { return SalesUuidCodec.encode(value); }
    private static UUID uuid(byte[] value) { return SalesUuidCodec.decode(value); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
