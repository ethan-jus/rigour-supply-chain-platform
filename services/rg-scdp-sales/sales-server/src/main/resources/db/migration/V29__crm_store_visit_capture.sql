-- CRM 新链路复用销售的采集、照片、录音、AI；正式有效性仅由 CRM 持有。
ALTER TABLE sales_visit
 ADD COLUMN crm_store_id BIGINT NULL,
 ADD COLUMN crm_visit_id BIGINT NULL,
 ADD COLUMN crm_ownership_id BIGINT NULL,
 ADD COLUMN crm_employee_code VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD UNIQUE KEY uk_sales_crm_visit(tenant_id,crm_visit_id),
 ADD KEY ix_sales_crm_cycle(tenant_id,crm_store_id,crm_ownership_id);
