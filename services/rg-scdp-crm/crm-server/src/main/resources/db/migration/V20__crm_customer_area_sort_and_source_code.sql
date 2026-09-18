-- 地区目录沿用稳定内部编码，来源编号单独展示，排序只在同级比较。
ALTER TABLE crm_customer_area
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '同级显示顺序',
    ADD COLUMN source_area_code VARCHAR(128) NULL COMMENT '来源地区展示编号';
CREATE INDEX idx_crm_area_sort ON crm_customer_area(tenant_id, deleted, sort_order, area_code);
