-- 人工台账，不触发库存、采购、付款及 BI 核算。
ALTER TABLE erp_cost_entry DROP CHECK ck_cost_category;
ALTER TABLE erp_cost_entry ADD CONSTRAINT ck_cost_category CHECK(category_code IN ('MARKETING','RENTAL','PURCHASE','LOSS'));
ALTER TABLE erp_cost_entry
 ADD COLUMN cost_month VARCHAR(7) NULL,
 ADD INDEX ix_cost_month (tenant_id,category_code,cost_month),
 ADD INDEX ix_cost_creator (tenant_id,category_code,created_by);
