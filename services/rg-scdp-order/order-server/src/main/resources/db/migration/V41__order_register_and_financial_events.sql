-- Order V41：订货宝订单登记读取、来源/同步审计、订单号映射与资金历史事件。
-- 仅新增，不修改已执行的 V1-V40。

ALTER TABLE order_sales_order
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '来源真实创建时间' AFTER source_creator_name,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '来源真实修改时间' AFTER source_created_at,
    ADD COLUMN source_modifier_id VARCHAR(80) NULL COMMENT '来源真实修改人ID' AFTER source_updated_at,
    ADD COLUMN source_modifier_name VARCHAR(100) NULL COMMENT '来源真实修改人名称' AFTER source_modifier_id,
    ADD COLUMN synced_by VARCHAR(50) NULL COMMENT '最近成功同步人' AFTER updated_by,
    ADD COLUMN synced_at DATETIME(6) NULL COMMENT '最近成功同步时间' AFTER synced_by;

ALTER TABLE order_payment_record
    ADD COLUMN payment_status_code VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT '单笔收款状态：PENDING/RECEIVED/CANCELLED/CHECKED' AFTER paid_amount,
    ADD COLUMN transaction_no VARCHAR(128) NULL COMMENT '支付或银行渠道真实流水号' AFTER payment_status_code,
    ADD COLUMN source_record_id VARCHAR(128) NULL COMMENT '来源稳定收款记录ID' AFTER source_document_no,
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '来源真实创建时间' AFTER source_record_id,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '来源真实修改时间' AFTER source_created_at,
    ADD COLUMN source_modifier_id VARCHAR(80) NULL COMMENT '来源真实修改人ID' AFTER source_updated_at,
    ADD COLUMN source_modifier_name VARCHAR(100) NULL COMMENT '来源真实修改人名称' AFTER source_modifier_id,
    ADD COLUMN synced_by VARCHAR(50) NULL COMMENT '最近成功同步人' AFTER updated_by,
    ADD COLUMN synced_at DATETIME(6) NULL COMMENT '最近成功同步时间' AFTER synced_by,
    ADD COLUMN checked_by VARCHAR(50) NULL COMMENT '财务核对人' AFTER synced_at,
    ADD COLUMN checked_at DATETIME(6) NULL COMMENT '财务核对时间' AFTER checked_by;

UPDATE order_payment_record
SET source_record_id = source_document_no
WHERE source_record_id IS NULL
  AND source_document_no IS NOT NULL;

CREATE TABLE order_number_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    connector_id CHAR(36) NULL,
    source_system_code VARCHAR(32) NOT NULL,
    source_object_type VARCHAR(64) NOT NULL,
    source_object_id VARCHAR(128) NOT NULL,
    dhb_order_no VARCHAR(128) NULL,
    internal_order_no VARCHAR(50) NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    evidence VARCHAR(1000) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_by VARCHAR(50) NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(50) NULL,
    updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_number_mapping_source (tenant_id, connector_id, source_object_type, source_object_id),
    KEY idx_order_number_mapping_order (tenant_id, internal_order_no),
    KEY idx_order_number_mapping_dhb (tenant_id, dhb_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_financial_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    payment_id BIGINT NULL,
    source_key VARCHAR(160) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    receivable_delta DECIMAL(24,6) NOT NULL DEFAULT 0.000000,
    received_delta DECIMAL(24,6) NOT NULL DEFAULT 0.000000,
    effective_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    evidence VARCHAR(1000) NULL,
    revision INT NOT NULL DEFAULT 1,
    created_by VARCHAR(50) NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(50) NULL,
    updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_financial_event_source (tenant_id, source_key),
    KEY idx_order_financial_event_order (tenant_id, order_id, effective_at),
    CONSTRAINT fk_order_financial_event_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES order_sales_order (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
