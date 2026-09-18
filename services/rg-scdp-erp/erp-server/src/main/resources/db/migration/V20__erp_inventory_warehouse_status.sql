-- ERP仓库补充业务状态字段；V19 已暂存但不改写，继续用新迁移推进。
ALTER TABLE erp_inventory_warehouse
    ADD COLUMN status_code VARCHAR(64) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '仓库状态，关联 WAREHOUSE_STATUS 字典项' AFTER contact_phone,
    ADD KEY idx_erp_inventory_warehouse_status (tenant_id, status_code, deleted, updated_time);
