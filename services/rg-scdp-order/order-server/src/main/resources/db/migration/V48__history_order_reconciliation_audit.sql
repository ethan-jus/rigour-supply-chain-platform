-- 拆合历史组改为订货宝单号粒度：在同一事务保存原值和回款拆分依据。
CREATE TABLE order_history_reconciliation_audit (
    tenant_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    group_id VARCHAR(36) NOT NULL,
    request_json LONGTEXT NOT NULL,
    before_json LONGTEXT NOT NULL,
    result_json LONGTEXT NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, operation_id),
    UNIQUE KEY uk_history_reconciliation_group (tenant_id, group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
