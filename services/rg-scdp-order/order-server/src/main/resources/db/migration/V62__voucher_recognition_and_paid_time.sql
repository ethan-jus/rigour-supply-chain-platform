-- 保留旧环境已存在的原图时间字段；只新增，不改写历史迁移。

SET @voucher_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_payment_voucher_transaction' AND COLUMN_NAME='voucher_paid_time')=0, 'ALTER TABLE order_payment_voucher_transaction ADD COLUMN voucher_paid_time DATETIME(6) NULL', 'SELECT 1');
PREPARE voucher_stmt FROM @voucher_ddl;
EXECUTE voucher_stmt;
DEALLOCATE PREPARE voucher_stmt;

SET @voucher_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_payment_voucher_transaction' AND COLUMN_NAME='voucher_paid_time_precision')=0, 'ALTER TABLE order_payment_voucher_transaction ADD COLUMN voucher_paid_time_precision VARCHAR(8) NULL', 'SELECT 1');
PREPARE voucher_stmt FROM @voucher_ddl;
EXECUTE voucher_stmt;
DEALLOCATE PREPARE voucher_stmt;

SET @voucher_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_payment_voucher_transaction' AND COLUMN_NAME='recognition_status')=0, 'ALTER TABLE order_payment_voucher_transaction ADD COLUMN recognition_status VARCHAR(24) NULL', 'SELECT 1');
PREPARE voucher_stmt FROM @voucher_ddl;
EXECUTE voucher_stmt;
DEALLOCATE PREPARE voucher_stmt;

SET @voucher_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_payment_voucher_transaction' AND COLUMN_NAME='payee')=0, 'ALTER TABLE order_payment_voucher_transaction ADD COLUMN payee VARCHAR(255) NULL', 'SELECT 1');
PREPARE voucher_stmt FROM @voucher_ddl;
EXECUTE voucher_stmt;
DEALLOCATE PREPARE voucher_stmt;

SET @voucher_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_payment_voucher_transaction' AND COLUMN_NAME='payment_result')=0, 'ALTER TABLE order_payment_voucher_transaction ADD COLUMN payment_result VARCHAR(24) NULL', 'SELECT 1');
PREPARE voucher_stmt FROM @voucher_ddl;
EXECUTE voucher_stmt;
DEALLOCATE PREPARE voucher_stmt;

CREATE TABLE order_voucher_recognition_job (
    id CHAR(64) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    voucher_key VARCHAR(512) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    lease_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    result_json JSON NULL,
    error_code VARCHAR(80) NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_voucher_recognition_queue (status,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE order_sync_receipt ADD COLUMN confirmed_by VARCHAR(50) NULL, ADD COLUMN confirmed_at DATETIME(6) NULL;
