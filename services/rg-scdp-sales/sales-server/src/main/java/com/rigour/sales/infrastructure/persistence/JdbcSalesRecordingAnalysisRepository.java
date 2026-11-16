package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesAudioAiClient.TranscriptSegment;
import com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository;
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

    public JdbcSalesRecordingAnalysisRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void enqueue(UUID tenantId, UUID visitId, UUID clipId, Instant now) {
        jdbc.update("""
                INSERT INTO sales_recording_analysis (tenant_id,clip_id,visit_id,next_attempt_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE clip_id=clip_id
                """, bin(tenantId), bin(clipId), bin(visitId), ts(now), ts(now), ts(now));
    }

    @Override
    @Transactional
    public Optional<Job> claim(Instant now, UUID leaseId, Instant leaseUntil) {
        // Row lease persists across instances; no transaction stays open during the supplier call.
        var jobs = jdbc.query("""
                SELECT a.tenant_id,a.visit_id,a.clip_id,c.object_key,a.status,a.asr_task_id,a.transcript,a.attempts,a.created_at
                FROM sales_recording_analysis a JOIN sales_recording_clip c ON c.tenant_id=a.tenant_id AND c.id=a.clip_id
                WHERE (a.status IN ('PENDING','PROCESSING') OR (a.status='SUCCEEDED' AND a.summary_status='PENDING'))
                  AND a.next_attempt_at<=? AND (a.lease_until IS NULL OR a.lease_until<?)
                ORDER BY a.next_attempt_at,a.created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (r,n) -> new Job(uuid(r.getBytes(1)),uuid(r.getBytes(2)),uuid(r.getBytes(3)),leaseId,
                r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getInt(8),r.getTimestamp(9).toInstant()),ts(now),ts(now));
        if (jobs.isEmpty()) return Optional.empty();
        var job = jobs.getFirst();
        jdbc.update("UPDATE sales_recording_analysis SET lease_id=?,lease_until=? WHERE tenant_id=? AND clip_id=?",
                bin(leaseId),ts(leaseUntil),bin(job.tenantId()),bin(job.clipId()));
        return Optional.of(job);
    }

    private static final String OWN_LEASE = " WHERE tenant_id=? AND clip_id=? AND lease_id=?";

    @Override
    public void processing(Job job, String taskId, String requestId, Instant now) {
        jdbc.update("""
                UPDATE sales_recording_analysis SET status='PROCESSING',asr_task_id=?,provider_request_id=?,attempts=0,
                error_code=NULL,lease_id=NULL,lease_until=NULL,next_attempt_at=?,updated_at=?
                """ + OWN_LEASE, taskId,requestId,ts(now.plusSeconds(8)),ts(now),bin(job.tenantId()),bin(job.clipId()),bin(job.leaseId()));
    }

    @Override
    public void transcribed(Job job, String transcript, List<TranscriptSegment> segments, String requestId, Instant now) {
        jdbc.update("""
                UPDATE sales_recording_analysis SET status='SUCCEEDED',transcript=?,segments_json=?,provider_request_id=?,
                summary_status=?,attempts=0,error_code=NULL,lease_id=NULL,lease_until=NULL,next_attempt_at=?,updated_at=?
                """ + OWN_LEASE, transcript,json.writeValueAsString(segments),requestId,
                transcript.isBlank()?"EMPTY":"PENDING",ts(now),ts(now),bin(job.tenantId()),bin(job.clipId()),bin(job.leaseId()));
    }

    @Override
    public void summarized(Job job, String summary, String model, String requestId, Instant now) {
        jdbc.update("""
                UPDATE sales_recording_analysis SET summary_status='SUCCEEDED',summary=?,summary_model=?,provider_request_id=?,
                error_code=NULL,lease_id=NULL,lease_until=NULL,updated_at=?
                """ + OWN_LEASE, summary,model,requestId,ts(now),bin(job.tenantId()),bin(job.clipId()),bin(job.leaseId()));
    }

    @Override
    public void deferred(Job job, String errorCode, boolean terminal, Instant now) {
        boolean failed = terminal || job.attempts() >= 4;
        boolean summary = "SUCCEEDED".equals(job.status());
        jdbc.update("""
                UPDATE sales_recording_analysis SET status=?,summary_status=?,error_code=?,attempts=attempts+1,
                lease_id=NULL,lease_until=NULL,next_attempt_at=?,updated_at=?
                """ + OWN_LEASE, summary?"SUCCEEDED":failed?"FAILED":job.status(),
                summary?(failed?"FAILED":"PENDING"):"WAITING",errorCode,
                ts(now.plusSeconds(Math.min(1800, 30L << Math.min(job.attempts(),5)))),ts(now),bin(job.tenantId()),bin(job.clipId()),bin(job.leaseId()));
    }

    @Override
    public void waiting(Job job, Instant now) {
        jdbc.update("UPDATE sales_recording_analysis SET lease_id=NULL,lease_until=NULL,next_attempt_at=?,updated_at=?" + OWN_LEASE,
                ts(now.plusSeconds(10)),ts(now),bin(job.tenantId()),bin(job.clipId()),bin(job.leaseId()));
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
                UPDATE sales_recording_analysis SET status=IF(status='FAILED',IF(asr_task_id IS NULL OR error_code='ASR_TASK_FAILED','PENDING','PROCESSING'),status),
                asr_task_id=IF(error_code='ASR_TASK_FAILED',NULL,asr_task_id),
                summary_status=IF(summary_status='FAILED','PENDING',summary_status),attempts=0,error_code=NULL,next_attempt_at=?,updated_at=?
                WHERE tenant_id=? AND visit_id=? AND clip_id=? AND (status='FAILED' OR summary_status='FAILED')
                  AND (lease_until IS NULL OR lease_until<?)
                """,ts(now),ts(now),bin(tenantId),bin(visitId),bin(clipId),ts(now));
    }

    private List<TranscriptSegment> segments(String value) {
        if (value == null) return List.of();
        return List.of(json.readValue(value, TranscriptSegment[].class));
    }
    private static byte[] bin(UUID value) { return SalesUuidCodec.encode(value); }
    private static UUID uuid(byte[] value) { return SalesUuidCodec.decode(value); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
