CREATE TABLE integration_sync_schedule (
 tenant_id CHAR(36) NOT NULL, scope_key VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL, mode VARCHAR(20) NOT NULL, interval_minutes INT NULL, daily_time CHAR(5) NULL,
 version BIGINT NOT NULL DEFAULT 1, next_run_at DATETIME(6) NULL, running_job_id CHAR(36) NULL,
 heartbeat_at DATETIME(6) NULL, last_started_at DATETIME(6) NULL, last_finished_at DATETIME(6) NULL,
 last_status VARCHAR(24) NULL, last_message VARCHAR(500) NULL,
 updated_at DATETIME(6) NOT NULL, updated_by CHAR(36) NOT NULL,
 PRIMARY KEY (tenant_id,scope_key), KEY idx_schedule_due(enabled,next_run_at),
 CHECK (mode IN ('FIXED_DELAY','DAILY')),
 CHECK ((mode='FIXED_DELAY' AND interval_minutes BETWEEN 5 AND 1440) OR (mode='DAILY' AND daily_time IS NOT NULL))
);
CREATE TABLE integration_sync_schedule_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id CHAR(36) NOT NULL, scope_key VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL, actor_id CHAR(36) NOT NULL, changed_at DATETIME(6) NOT NULL,
 enabled BOOLEAN NOT NULL, mode VARCHAR(20) NOT NULL, interval_minutes INT NULL, daily_time CHAR(5) NULL,
 UNIQUE KEY uk_schedule_audit(tenant_id,scope_key,version)
);
