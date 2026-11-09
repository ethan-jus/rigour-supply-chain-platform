ALTER TABLE erp_inventory_warehouse
    ADD COLUMN cost_department_id BIGINT NULL COMMENT '采购成本归属部门ID，关联HR部门，空值待维护';
