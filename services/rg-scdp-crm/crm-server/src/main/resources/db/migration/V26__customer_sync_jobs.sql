CREATE TABLE crm_customer_sync_job (
    tenant_id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    connector_id CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    stage VARCHAR(300) NOT NULL,
    active_slot TINYINT NULL,
    started_at DATETIME(6) NOT NULL,
    heartbeat_at DATETIME(6) NOT NULL,
    result_json JSON NULL,
    PRIMARY KEY (tenant_id, job_id),
    UNIQUE KEY uk_crm_customer_job_active (tenant_id, connector_id, active_slot)
);
