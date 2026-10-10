CREATE TABLE order_payment_voucher_exclusion (
    tenant_id VARCHAR(36) NOT NULL,
    payment_id BIGINT NOT NULL,
    voucher_key VARCHAR(500) NOT NULL,
    retained_key VARCHAR(500) NULL,
    reason VARCHAR(1000) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, payment_id, voucher_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
