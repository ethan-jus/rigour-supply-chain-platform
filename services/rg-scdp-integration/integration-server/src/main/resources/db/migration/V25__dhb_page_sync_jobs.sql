CREATE TABLE integration_dhb_page_job (
    tenant_id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    connector_id CHAR(36) NOT NULL,
    scope VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    stage VARCHAR(300) NOT NULL,
    active_slot TINYINT NULL,
    started_at DATETIME(6) NOT NULL,
    heartbeat_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    result_json JSON NULL,
    PRIMARY KEY (tenant_id, job_id),
    UNIQUE KEY uk_dhb_page_active (tenant_id, connector_id, active_slot),
    KEY idx_dhb_page_latest (tenant_id, connector_id, scope, started_at)
);
