ALTER TABLE hr_expense ADD COLUMN cost_month VARCHAR(7) NULL;
UPDATE hr_expense SET cost_month = DATE_FORMAT(cost_date, '%Y-%m') WHERE cost_month IS NULL;
CREATE INDEX idx_hr_expense_period ON hr_expense(tenant_id,cost_month,status_code,employee_code);
CREATE TABLE hr_expense_cost_link (
 tenant_id VARCHAR(64) NOT NULL, expense_id VARCHAR(36) NOT NULL,
 source_service VARCHAR(16) NOT NULL, source_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(tenant_id,expense_id), UNIQUE KEY uk_expense_original(tenant_id,source_service,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_expense_attachment (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, entry_id VARCHAR(36) NOT NULL,
 file_key VARCHAR(500) NOT NULL, file_name VARCHAR(200) NOT NULL, content_type VARCHAR(100) NOT NULL,
 file_size BIGINT NOT NULL, created_by VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), KEY idx_expense_attachment(tenant_id,entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
