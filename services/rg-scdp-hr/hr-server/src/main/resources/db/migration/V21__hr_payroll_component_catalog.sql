-- Rules are maintained once; published schemes retain immutable component references and snapshots.
CREATE TABLE hr_payroll_component_version (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, request_id VARCHAR(80) NOT NULL,
 rule_id VARCHAR(36) NOT NULL, version INT NOT NULL, kind VARCHAR(20) NOT NULL,
 data_json JSON NOT NULL, reason VARCHAR(1000) NOT NULL, created_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL,
 PRIMARY KEY (id), UNIQUE KEY uk_payroll_component_request (tenant_id,request_id),
 UNIQUE KEY uk_payroll_component_version (tenant_id,rule_id,version),
 KEY ix_payroll_component_kind (tenant_id,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_configuration (
 tenant_id VARCHAR(64) NOT NULL, unified_from CHAR(7) NOT NULL,
 reason VARCHAR(1000) NOT NULL, updated_by VARCHAR(50) NOT NULL, updated_time DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
