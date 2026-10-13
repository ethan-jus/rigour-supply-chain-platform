-- 月度人工补贴核定、结构化发放及跨月补差；保留既有核算快照。
ALTER TABLE hr_payroll_input ADD COLUMN allowance_override DECIMAL(18,2) NULL;
CREATE TABLE hr_payroll_payment (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, payroll_id VARCHAR(36) NOT NULL,
 request_id VARCHAR(36) NOT NULL, paid_date DATE NOT NULL, amount DECIMAL(18,2) NOT NULL,
 reference_no VARCHAR(128) NOT NULL, reason VARCHAR(500) NOT NULL, created_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_pay_payment_request(tenant_id,request_id),
 UNIQUE KEY uk_pay_payment_reference(tenant_id,payroll_id,reference_no),
 KEY ix_pay_payment_payroll(tenant_id,payroll_id), CONSTRAINT ck_pay_payment_amount CHECK(amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_correction (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, source_payroll_id VARCHAR(36) NOT NULL,
 source_month CHAR(7) NOT NULL, employee_code VARCHAR(50) NOT NULL, target_month CHAR(7) NOT NULL,
 request_id VARCHAR(36) NOT NULL, amount DECIMAL(18,2) NOT NULL, reason VARCHAR(500) NOT NULL,
 status_code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', revision INT NOT NULL DEFAULT 1,
 created_by VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_pay_correction_request(tenant_id,request_id),
 KEY ix_pay_correction_target(tenant_id,employee_code,target_month),
 KEY ix_pay_correction_source(tenant_id,source_payroll_id),
 CONSTRAINT ck_pay_correction_amount CHECK(amount<>0),
 CONSTRAINT ck_pay_correction_month CHECK(target_month>source_month),
 CONSTRAINT ck_pay_correction_status CHECK(status_code IN ('ACTIVE','VOID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
