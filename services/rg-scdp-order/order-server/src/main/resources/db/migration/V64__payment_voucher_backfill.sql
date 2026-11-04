-- 缺图检查独立于业务增量游标；不修改已有回款事实。
CREATE TABLE order_payment_voucher_backfill (
    tenant_id VARCHAR(36) NOT NULL,
    connector_id VARCHAR(36) NOT NULL,
    payment_id BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    lease_token CHAR(36) NOT NULL,
    next_check_at DATETIME(6) NOT NULL,
    checked_at DATETIME(6) NULL,
    PRIMARY KEY (tenant_id,connector_id,payment_id),
    KEY idx_voucher_backfill_due (next_check_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
