CREATE TABLE order_payment_voucher_review (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    group_key VARCHAR(80) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    conclusion VARCHAR(32) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    payment_ids_json LONGTEXT NOT NULL,
    created_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id,id),
    KEY idx_voucher_review_group (tenant_id,group_key,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
