-- 已开通城市来源于销售部下的城市部门；客户地区仅保留为授权关联。
CREATE TABLE bi_source_hr_hr_department (
 id BIGINT NOT NULL, tenant_id VARCHAR(64) NOT NULL, department_code VARCHAR(50) NOT NULL,
 department_name VARCHAR(128) NOT NULL, parent_id BIGINT NULL, status_code VARCHAR(16) NOT NULL,
 deleted INT NOT NULL DEFAULT 0, department_path JSON NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uk_bi_department_code(tenant_id,department_code),
 KEY ix_bi_department_parent(tenant_id,parent_id,status_code,deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE bi_sales_contact_city_dim
 ADD department_id BIGINT NULL, ADD department_path JSON NULL, ADD source_region_code VARCHAR(128) NULL;
