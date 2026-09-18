-- CRM V3：保存订货宝归属地区的来源父级关系。
-- getArea 未返回 parentID 时保持 NULL，不按地区名称猜测父级。
ALTER TABLE crm_customer_area
    ADD COLUMN parent_area_code VARCHAR(128) NULL
        COMMENT '订货宝 parentID，对应上级地区的来源 AreaID'
        AFTER area_name;

CREATE INDEX idx_crm_customer_area_parent
    ON crm_customer_area (tenant_id, parent_area_code, status);
