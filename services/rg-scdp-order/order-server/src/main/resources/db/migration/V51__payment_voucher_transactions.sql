-- 逐张凭证的核实信息，不生成新回款、不参与回款金额统计。
-- 同一银行付款可能分摊至多个订单，保留真实关联供查重，不把单号拼接成虚构流水。
CREATE TABLE order_payment_voucher_transaction (
    tenant_id VARCHAR(36) NOT NULL,
    payment_id BIGINT NOT NULL,
    voucher_key VARCHAR(512) NOT NULL,
    voucher_amount DECIMAL(18,2) NULL,
    transaction_no VARCHAR(128) NULL,
    evidence_note VARCHAR(1000) NULL,
    created_by VARCHAR(128) NOT NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, payment_id, voucher_key),
    KEY idx_payment_voucher_transaction (tenant_id, transaction_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
