-- ERP商品补充排序字段，供商品管理列表按业务顺序展示。
-- 存量行统一落 0，数值越小越靠前；排序值相同时列表仍按 updated_time 倒序兜底。
ALTER TABLE erp_product
    ADD COLUMN ordinal INT NOT NULL DEFAULT 0
        COMMENT '排序值，数值越小越靠前' AFTER shelf_status_code,
    ADD KEY idx_erp_product_ordinal (tenant_id, deleted, ordinal);
