CREATE TABLE hr_payroll_scheme_version (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL, request_id VARCHAR(80) NOT NULL,
 scheme_id VARCHAR(36) NOT NULL, version INT NOT NULL, revision INT NOT NULL DEFAULT 0,
 status VARCHAR(20) NOT NULL, data_json JSON NOT NULL, reason VARCHAR(1000) NOT NULL,
 created_by VARCHAR(36) NOT NULL, published_by VARCHAR(36) NULL,
 updated_time DATETIME(6) NOT NULL, published_time DATETIME(6) NULL, stop_from DATE NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_scheme_request(tenant_id,request_id),
 UNIQUE KEY uk_scheme_version(tenant_id,scheme_id,version), KEY ix_scheme_status(tenant_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_scheme_month_input (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL, employee_code VARCHAR(50) NOT NULL,
 payroll_month VARCHAR(7) NOT NULL, revision INT NOT NULL DEFAULT 0, data_json JSON NOT NULL,
 updated_by VARCHAR(36) NOT NULL, updated_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_scheme_month_input(tenant_id,employee_code,payroll_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_scheme_event (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL, subject_id VARCHAR(100) NOT NULL,
 event_type VARCHAR(32) NOT NULL, data_json JSON NOT NULL, reason VARCHAR(1000) NOT NULL,
 actor VARCHAR(36) NOT NULL, created_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), KEY ix_scheme_event(tenant_id,subject_id,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
