-- 费用写入与版本递增由同一事务提交；已有记录以版本0首次全量读取。
CREATE TABLE hr_expense_guard (
 tenant_id VARCHAR(36) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
