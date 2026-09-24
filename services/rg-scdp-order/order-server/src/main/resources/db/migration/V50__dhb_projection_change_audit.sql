CREATE TABLE order_dhb_projection_change_audit (
    tenant_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    connector_id VARCHAR(36) NOT NULL,
    object_type VARCHAR(24) NOT NULL,
    source_no VARCHAR(128) NOT NULL,
    before_json LONGTEXT NOT NULL,
    source_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    KEY idx_dhb_projection_source (tenant_id, connector_id, object_type, source_no)
);
