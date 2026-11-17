-- 完整导入原件仅供后台追溯；运输记录仅关联本笔摘录，避免跨单据泄露整份账单。
CREATE TABLE order_transport_fee_import_file (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    file_key VARCHAR(1000) NOT NULL,
    file_size BIGINT NOT NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_transport_fee_file (tenant_id, actor_id, file_hash),
    KEY idx_transport_fee_file_tenant_created (tenant_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
