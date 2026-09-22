package com.rigour.merchant.infrastructure.persistence.repository;
import com.rigour.merchant.api.v1.model.*;
import com.rigour.merchant.application.port.out.CustomerSyncJobStore;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcCustomerSyncJobStore implements CustomerSyncJobStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcCustomerSyncJobStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public CustomerSyncJob reserve(UUID tenant, UUID jobId, UUID connector, Instant now) {
        var old=find(tenant,jobId);
        if(old.isPresent()) {
            if(!old.get().connectorId().equals(connector)) throw new IllegalArgumentException("任务连接器不一致");
            return old.get();
        }
        try {
            jdbc.update("INSERT INTO crm_customer_sync_job(tenant_id,job_id,connector_id,status,stage,active_slot,started_at,heartbeat_at)"
                    + " VALUES(?,?,?,'QUEUED','等待客户后台执行',1,?,?)",
                    tenant.toString(),jobId.toString(),connector.toString(),utc(now),utc(now));
        } catch(DuplicateKeyException busy) {
            return rows("SELECT * FROM crm_customer_sync_job WHERE tenant_id=? AND connector_id=? AND active_slot=1",
                    tenant.toString(),connector.toString()).stream().findFirst().or(()->find(tenant,jobId)).orElseThrow(()->busy);
        }
        return find(tenant,jobId).orElseThrow();
    }
    public Optional<CustomerSyncJob> find(UUID tenant,UUID jobId) {
        return rows("SELECT * FROM crm_customer_sync_job WHERE tenant_id=? AND job_id=?",
                tenant.toString(),jobId.toString()).stream().findFirst();
    }
    public boolean claim(UUID tenant,UUID jobId,Instant now) {
        return jdbc.update("UPDATE crm_customer_sync_job SET status='RUNNING',heartbeat_at=?"
                + " WHERE tenant_id=? AND job_id=? AND status='QUEUED'",utc(now),tenant.toString(),jobId.toString())==1;
    }
    public void update(UUID tenant,UUID jobId,String status,String stage,SyncResult result,Instant now) {
        jdbc.update("UPDATE crm_customer_sync_job SET status=?,stage=?,result_json=?,heartbeat_at=?,active_slot=?"
                + " WHERE tenant_id=? AND job_id=? AND status IN ('QUEUED','RUNNING')",status,stage,
                result==null?null:json.writeValueAsString(result),utc(now),
                Set.of("QUEUED","RUNNING","UNKNOWN").contains(status)?1:null,tenant.toString(),jobId.toString());
    }
    public void heartbeat(UUID tenant,UUID jobId,Instant now) {
        jdbc.update("UPDATE crm_customer_sync_job SET heartbeat_at=? WHERE tenant_id=? AND job_id=?"
                + " AND status IN ('QUEUED','RUNNING')",utc(now),tenant.toString(),jobId.toString());
    }
    private List<CustomerSyncJob> rows(String sql,Object...args) {
        return jdbc.query(sql,(r,i)->new CustomerSyncJob(UUID.fromString(r.getString("job_id")),
                UUID.fromString(r.getString("connector_id")),r.getString("status"),r.getString("stage"),
                r.getTimestamp("started_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                r.getTimestamp("heartbeat_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                r.getString("result_json")==null?null:json.readValue(r.getString("result_json"),SyncResult.class)),args);
    }
    private static LocalDateTime utc(Instant v) { return LocalDateTime.ofInstant(v,ZoneOffset.UTC); }
}
