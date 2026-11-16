-- 人工历史修正优先于后续来源同步；解除保护必须另行核对并保留维护审计。
CREATE TABLE order_manual_sync_protection (
    tenant_id VARCHAR(36) NOT NULL,
    connector_id VARCHAR(36) NOT NULL,
    source_no VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, connector_id, source_no, order_id),
    KEY idx_manual_sync_order (tenant_id, order_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
