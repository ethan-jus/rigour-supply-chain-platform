-- 回款核对并发保护：交易单号在有效记录内唯一。
-- 生成的键对空交易单号取 NULL，NULL 不参与唯一约束，未核对/无流水号的记录互不冲突。
-- V41 已执行，这里只做增量：不改 V41。
ALTER TABLE order_payment_record
    ADD COLUMN transaction_no_key VARCHAR(128)
        GENERATED ALWAYS AS (NULLIF(TRIM(transaction_no), '')) STORED
        COMMENT '交易单号唯一键：空值不参与唯一约束';

ALTER TABLE order_payment_record
    ADD UNIQUE KEY uk_order_payment_transaction (tenant_id, transaction_no_key);
