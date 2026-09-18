-- ERP Core Schema V7：限制同一租户同一对象类型的同步批次并发执行。
CREATE TABLE erp_master_data_sync_lock (
    id            CHAR(36)     NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    source_system VARCHAR(32)  NOT NULL,
    object_type   VARCHAR(32)  NOT NULL,
    run_id        CHAR(36)     NOT NULL,
    acquired_at   DATETIME(6)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_master_data_sync_lock_scope
        UNIQUE (tenant_id, source_system, object_type),
    KEY idx_erp_master_data_sync_lock_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP同步互斥锁';
