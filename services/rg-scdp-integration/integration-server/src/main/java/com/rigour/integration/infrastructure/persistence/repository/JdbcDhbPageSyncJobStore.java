package com.rigour.integration.infrastructure.persistence.repository;

import com.rigour.integration.api.v1.model.*;
import com.rigour.integration.application.port.out.DhbPageSyncJobStore;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcDhbPageSyncJobStore implements DhbPageSyncJobStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcDhbPageSyncJobStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }
    public DhbPageSyncJob reserve(UUID tenant, UUID jobId, DhbPageSyncCommand c, Instant now) {
        var existing = find(tenant, jobId);
        if (existing.isPresent()) {
            if (!existing.get().connectorId().equals(c.connectorId())
                    || !existing.get().scope().equals(c.scope().name()))
                throw new IllegalArgumentException("任务编号已被其他同步使用");
            return existing.get();
        }
        try {
            jdbc.update("INSERT INTO integration_dhb_page_job"
                    + "(tenant_id,job_id,connector_id,scope,status,stage,active_slot,started_at,heartbeat_at)"
                    + " VALUES(?,?,?,?,'QUEUED','等待后台执行',1,?,?)",
                    tenant.toString(), jobId.toString(), c.connectorId().toString(), c.scope().name(), utc(now), utc(now));
        } catch (DuplicateKeyException busy) {
            return rows("SELECT * FROM integration_dhb_page_job WHERE tenant_id=? AND connector_id=? AND active_slot=1",
                    tenant.toString(), c.connectorId().toString()).stream().findFirst()
                    .or(() -> find(tenant, jobId)).orElseThrow(() -> busy);
        }
        return find(tenant, jobId).orElseThrow();
    }
    public Optional<DhbPageSyncJob> find(UUID tenant, UUID jobId) {
        return rows("SELECT * FROM integration_dhb_page_job WHERE tenant_id=? AND job_id=?",
                tenant.toString(), jobId.toString()).stream().findFirst();
    }
    public Optional<DhbPageSyncJob> latest(UUID tenant, UUID connector, String scope) {
        return rows("SELECT * FROM integration_dhb_page_job WHERE tenant_id=? AND connector_id=?"
                + " AND (scope=? OR active_slot=1) ORDER BY active_slot DESC,started_at DESC LIMIT 1",
                tenant.toString(), connector.toString(), scope).stream().findFirst();
    }
    public boolean claim(UUID tenant, UUID jobId, Instant now) {
        return jdbc.update("UPDATE integration_dhb_page_job SET status='RUNNING',heartbeat_at=?"
                + " WHERE tenant_id=? AND job_id=? AND status='QUEUED'",
                utc(now), tenant.toString(), jobId.toString()) == 1;
    }
    public void progress(UUID tenant, UUID jobId, String stage, Instant now) {
        jdbc.update("UPDATE integration_dhb_page_job SET status='RUNNING',stage=?,heartbeat_at=?"
                + " WHERE tenant_id=? AND job_id=? AND status IN ('QUEUED','RUNNING')",
                stage, utc(now), tenant.toString(), jobId.toString());
    }
    public void heartbeat(UUID tenant, UUID jobId, Instant now) {
        jdbc.update("UPDATE integration_dhb_page_job SET heartbeat_at=? WHERE tenant_id=? AND job_id=?"
                + " AND status IN ('QUEUED','RUNNING')", utc(now), tenant.toString(), jobId.toString());
    }
    public void finish(UUID tenant, UUID jobId, String status, String stage,
            DhbSyncOrchestrationResult result, Instant now) {
        jdbc.update("UPDATE integration_dhb_page_job SET status=?,stage=?,result_json=?,finished_at=?,heartbeat_at=?,"
                + " active_slot=? WHERE tenant_id=? AND job_id=? AND status IN ('QUEUED','RUNNING','UNKNOWN')",
                status, stage, result == null ? null : json.writeValueAsString(result), utc(now), utc(now),
                "UNKNOWN".equals(status) ? 1 : null, tenant.toString(), jobId.toString());
    }
    private List<DhbPageSyncJob> rows(String sql, Object... args) {
        return jdbc.query(sql, (r, i) -> new DhbPageSyncJob(UUID.fromString(r.getString("job_id")),
                UUID.fromString(r.getString("connector_id")), r.getString("scope"), r.getString("status"),
                r.getString("stage"), instant(r.getTimestamp("started_at")), instant(r.getTimestamp("heartbeat_at")),
                instant(r.getTimestamp("finished_at")), r.getString("result_json") == null ? null
                : json.readValue(r.getString("result_json"), DhbSyncOrchestrationResult.class)), args);
    }
    private static LocalDateTime utc(Instant v) { return LocalDateTime.ofInstant(v, ZoneOffset.UTC); }
    private static Instant instant(java.sql.Timestamp v) { return v == null ? null : v.toLocalDateTime().toInstant(ZoneOffset.UTC); }
}
