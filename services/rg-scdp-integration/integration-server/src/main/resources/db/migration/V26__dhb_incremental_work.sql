CREATE TABLE integration_dhb_incremental_work (
    tenant_id CHAR(36) NOT NULL,
    connector_id CHAR(36) NOT NULL,
    object_type VARCHAR(24) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    payload JSON NOT NULL,
    source_updated_at DATETIME(6) NULL,
    applied TINYINT NOT NULL DEFAULT 0,
    checked_at DATETIME(6) NULL,
    retry_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, connector_id, object_type, source_id),
    KEY idx_dhb_work_pending (tenant_id, connector_id, object_type, applied, retry_at)
);
