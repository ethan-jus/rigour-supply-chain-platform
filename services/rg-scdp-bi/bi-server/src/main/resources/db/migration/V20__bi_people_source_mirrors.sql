-- 新的人事/拜访数据走所属领域 API，维持 BI 本地投影和租户边界。
ALTER TABLE bi_source_crm_crm_customer ADD customer_source_name VARCHAR(120) NULL, ADD business_category_name VARCHAR(120) NULL;
ALTER TABLE bi_source_crm_crm_customer_area ADD parent_area_code VARCHAR(128) NULL;
ALTER TABLE bi_employee_dim ADD department_id BIGINT NULL, ADD department_path JSON NULL;
CREATE TABLE bi_source_hr_hr_employee (
 id BIGINT NOT NULL,tenant_id VARCHAR(64) NOT NULL,employee_code VARCHAR(50) NOT NULL,
 employee_name VARCHAR(128) NOT NULL,employment_status VARCHAR(32) NOT NULL,city_name VARCHAR(160) NULL,
 position_name VARCHAR(120) NULL,department_name VARCHAR(128) NULL,department_id BIGINT NULL,department_path JSON NULL,
 entry_date DATETIME(6) NULL,leave_date DATETIME(6) NULL,PRIMARY KEY(tenant_id,id),KEY ix_bi_hr_employee(tenant_id,employee_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE bi_source_sales_sales_submitted_visit (
 id BINARY(16) NOT NULL,tenant_id BINARY(16) NOT NULL,store_id BINARY(16) NOT NULL,city_name VARCHAR(160) NULL,
 submitted_at DATETIME(6) NOT NULL,review_status VARCHAR(32) NOT NULL,salesperson_id BINARY(16) NULL,
 owner_staff_code VARCHAR(128) NULL,customer_id BIGINT NULL,customer_code VARCHAR(128) NULL,PRIMARY KEY(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- 导出字段发生变化，重新装载镜像后才标记为同步完成。
DELETE FROM bi_source_snapshot_checkpoint WHERE dataset IN ('CRM_CUSTOMER','CRM_CUSTOMER_AREA');
