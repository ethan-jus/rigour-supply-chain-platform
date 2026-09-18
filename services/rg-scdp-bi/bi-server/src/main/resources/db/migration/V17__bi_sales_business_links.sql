ALTER TABLE bi_sales_contact_fact
    ADD COLUMN salesperson_id VARCHAR(36) NULL,
    ADD COLUMN owner_staff_code VARCHAR(128) NULL COMMENT '提交人已核对的 HR 员工编码，非 CRM 客户负责人',
    ADD COLUMN customer_id BIGINT NULL,
    ADD COLUMN customer_code VARCHAR(128) NULL,
    ADD KEY idx_bi_contact_owner (tenant_id, owner_staff_code, submitted_at, region_code, store_id);

-- 旧快照未带固定编码时不能把个人查询误报为零。
ALTER TABLE bi_sales_contact_snapshot
    ADD COLUMN business_links_ready TINYINT NOT NULL DEFAULT 0;
