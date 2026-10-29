-- Store geography is independent of legacy customer business-area codes.
CREATE TABLE department_admin_scope (
 tenant_id VARCHAR(64) NOT NULL, department_id BIGINT NOT NULL,
 admin_area_version VARCHAR(32) NOT NULL, admin_area_code CHAR(6) NOT NULL,
 status_code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', revision BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 created_by VARCHAR(64) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,department_id,admin_area_version,admin_area_code),
 KEY ix_department_admin_area(tenant_id,admin_area_version,admin_area_code,status_code,department_id),
 CHECK(status_code IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
ALTER TABLE iam_app_member_scope_limit DROP CHECK ck_app_limit_dimension;
ALTER TABLE iam_app_member_scope_limit ADD CONSTRAINT ck_app_limit_dimension
 CHECK(dimension IN ('REGION','WAREHOUSE','ADMIN_REGION'));
