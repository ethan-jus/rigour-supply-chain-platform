ALTER TABLE order_logistics_cost_attachment
    ADD COLUMN category_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci
        NOT NULL DEFAULT 'EXPENSE_VOUCHER' COMMENT '凭证分类：费用凭证、运输回单、装货照片、卸货照片';

ALTER TABLE order_logistics_cost
    ADD COLUMN attachment_save_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci
        NULL COMMENT '最近一次运输单与凭证整体保存标识',
    ADD COLUMN attachment_save_hash VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci
        NULL COMMENT '整体保存内容指纹，用于响应丢失后安全重试',
    ADD COLUMN attachment_save_revision INT NULL COMMENT '整体保存完成后的版本号',
    ADD UNIQUE KEY uk_transport_attachment_save (tenant_id, attachment_save_id);
