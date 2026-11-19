CREATE TABLE hr_sales_statement (
 tenant_id VARCHAR(64) NOT NULL, payroll_id VARCHAR(64) NOT NULL, revision INT NOT NULL,
 employee_code VARCHAR(64) NOT NULL, payroll_month CHAR(7) NOT NULL, employee_view JSON NOT NULL,
 published_by VARCHAR(64) NOT NULL, published_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,payroll_id,revision), KEY ix_self_month(tenant_id,employee_code,payroll_month,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
