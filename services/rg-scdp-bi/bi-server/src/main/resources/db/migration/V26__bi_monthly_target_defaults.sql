-- Monthly default standards are versioned by effective month. Business overrides remain in bi_business_target.
CREATE TABLE bi_target_default (
 tenant_id VARCHAR(64) NOT NULL,
 effective_month DATE NOT NULL,
 dimension_type VARCHAR(32) NOT NULL,
 metric_code VARCHAR(64) NOT NULL,
 target_value DECIMAL(24,6) NOT NULL,
 revision INT NOT NULL DEFAULT 1,
 updated_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,dimension_type,metric_code,effective_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE bi_target_default_event (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 effective_month DATE NOT NULL,
 dimension_type VARCHAR(32) NOT NULL,
 metric_code VARCHAR(64) NOT NULL,
 target_value DECIMAL(24,6) NOT NULL,
 revision INT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 actor VARCHAR(64) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 KEY ix_target_default_history(tenant_id,dimension_type,effective_month,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
