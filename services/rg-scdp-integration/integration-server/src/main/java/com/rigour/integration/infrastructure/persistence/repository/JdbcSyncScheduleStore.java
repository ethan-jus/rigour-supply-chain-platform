package com.rigour.integration.infrastructure.persistence.repository;

import com.rigour.integration.application.port.out.SyncScheduleStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.scheduling.*;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Repository
public class JdbcSyncScheduleStore implements SyncScheduleStore {
    private final JdbcTemplate jdbc;

    public JdbcSyncScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ScheduleView> find(String tenant, String key) {
        return jdbc
                .query(
                        "SELECT * FROM integration_sync_schedule WHERE tenant_id=? AND scope_key=?",
                        (r, i) -> view(r),
                        tenant,
                        key)
                .stream()
                .findFirst();
    }

    @Transactional
    public ScheduleView save(
            String tenant, String key, SaveScheduleCommand command, String actor, Instant now) {
        var s = command.settings();
        try {
            if (command.expectedVersion() == 0) {
                jdbc.update(
                        "INSERT INTO"
                            + " integration_sync_schedule(tenant_id,scope_key,enabled,mode,interval_minutes,daily_time,next_run_at,updated_at,updated_by)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                        tenant,
                        key,
                        s.enabled(),
                        s.mode(),
                        s.intervalMinutes(),
                        s.dailyTime(),
                        utc(s.nextAfter(now)),
                        utc(now),
                        actor);
            } else {
                int changed =
                        jdbc.update(
                                "UPDATE integration_sync_schedule SET"
                                    + " enabled=?,mode=?,interval_minutes=?,daily_time=?,next_run_at=IF(running_job_id"
                                    + " IS NULL,?,NULL),version=version+1,updated_at=?,updated_by=?"
                                    + " WHERE tenant_id=? AND scope_key=? AND version=?",
                                s.enabled(),
                                s.mode(),
                                s.intervalMinutes(),
                                s.dailyTime(),
                                utc(s.nextAfter(now)),
                                utc(now),
                                actor,
                                tenant,
                                key,
                                command.expectedVersion());
                if (changed != 1) throw conflict();
            }
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
        jdbc.update(
                "INSERT INTO"
                    + " integration_sync_schedule_audit(tenant_id,scope_key,version,actor_id,changed_at,enabled,mode,interval_minutes,daily_time)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                tenant,
                key,
                command.expectedVersion() + 1,
                actor,
                utc(now),
                s.enabled(),
                s.mode(),
                s.intervalMinutes(),
                s.dailyTime());
        return find(tenant, key).orElseThrow();
    }

    public List<Entry> due(Instant now) {
        return jdbc.query(
                "SELECT * FROM integration_sync_schedule WHERE enabled=1 AND running_job_id IS NULL"
                    + " AND next_run_at<=? ORDER BY next_run_at LIMIT 100",
                (r, i) -> new Entry(r.getString("tenant_id"), view(r), instant(r, "heartbeat_at")),
                utc(now));
    }

    public List<Entry> running() {
        return jdbc.query(
                "SELECT * FROM integration_sync_schedule WHERE running_job_id IS NOT NULL",
                (r, i) -> new Entry(r.getString("tenant_id"), view(r), instant(r, "heartbeat_at")));
    }

    public boolean claim(String tenant, String key, long version, String job, Instant now) {
        return jdbc.update(
                        "UPDATE integration_sync_schedule SET"
                            + " running_job_id=?,last_started_at=?,heartbeat_at=?,last_status='RUNNING',last_finished_at=NULL,last_message=NULL,next_run_at=NULL"
                            + " WHERE tenant_id=? AND scope_key=? AND version=? AND enabled=1 AND"
                            + " running_job_id IS NULL AND next_run_at<=?",
                        job,
                        utc(now),
                        utc(now),
                        tenant,
                        key,
                        version,
                        utc(now))
                == 1;
    }

    public void heartbeat(String tenant, String key, String job, Instant now) {
        jdbc.update(
                "UPDATE integration_sync_schedule SET heartbeat_at=? WHERE tenant_id=? AND"
                    + " scope_key=? AND running_job_id=?",
                utc(now),
                tenant,
                key,
                job);
    }

    @Transactional
    public void finish(
            String tenant, String key, String job, String status, String message, Instant now) {
        var rows =
                jdbc.query(
                        "SELECT * FROM integration_sync_schedule WHERE tenant_id=? AND scope_key=?"
                            + " AND running_job_id=? FOR UPDATE",
                        (r, i) -> view(r),
                        tenant,
                        key,
                        job);
        if (rows.isEmpty()) return;
        // UNKNOWN 不释放运行占位，禁止自动重复提交执行结果不明的任务。
        boolean unknown = "UNKNOWN".equals(status);
        jdbc.update(
                "UPDATE integration_sync_schedule SET"
                    + " running_job_id=?,last_finished_at=?,last_status=?,last_message=?,next_run_at=?"
                    + " WHERE tenant_id=? AND scope_key=? AND running_job_id=?",
                unknown ? job : null,
                unknown ? null : utc(now),
                status,
                message,
                unknown ? null : utc(rows.getFirst().settings().nextAfter(now)),
                tenant,
                key,
                job);
    }

    private static ScheduleView view(ResultSet r) throws SQLException {
        return new ScheduleView(
                r.getString("scope_key"),
                new ScheduleSettings(
                        r.getBoolean("enabled"),
                        r.getString("mode"),
                        (Integer) r.getObject("interval_minutes"),
                        r.getString("daily_time")),
                r.getLong("version"),
                true,
                instant(r, "next_run_at"),
                instant(r, "last_started_at"),
                instant(r, "last_finished_at"),
                r.getString("last_status"),
                r.getString("last_message"),
                r.getString("running_job_id"));
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "配置已变化，请刷新后再保存", List.of());
    }

    private static LocalDateTime utc(Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet r, String key) throws SQLException {
        var v = r.getObject(key, LocalDateTime.class);
        return v == null ? null : v.toInstant(ZoneOffset.UTC);
    }
}
