-- 月度新增客户以 business_created_at（UTC）分桶，禁止回退到入库时间。
ALTER TABLE bi_source_crm_crm_customer
 ADD COLUMN business_created_at DATETIME(6) NULL,
 ADD COLUMN business_created_by_id VARCHAR(128) NULL,
 ADD COLUMN business_created_by_name VARCHAR(160) NULL,
 ADD COLUMN business_creation_source VARCHAR(32) NULL,
 ADD INDEX idx_bi_customer_business_created (tenant_id, deleted, business_created_at);
