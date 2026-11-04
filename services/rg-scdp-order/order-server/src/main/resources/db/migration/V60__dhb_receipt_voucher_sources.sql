CREATE TABLE order_payment_voucher_source (
    tenant_id VARCHAR(36) NOT NULL,
    connector_id VARCHAR(36) NOT NULL,
    source_payment_no VARCHAR(128) NOT NULL,
    receipt_no VARCHAR(128) NOT NULL,
    payment_id BIGINT NOT NULL,
    voucher_key VARCHAR(500) NOT NULL,
    managed_link TINYINT NOT NULL DEFAULT 1,
    created_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, connector_id, source_payment_no, payment_id, voucher_key),
    KEY idx_voucher_source_payment (tenant_id, payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
