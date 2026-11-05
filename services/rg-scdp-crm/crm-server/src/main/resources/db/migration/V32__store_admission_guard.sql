-- Serialize store admission and identity maintenance independently of ownership policy locks.
-- Rows are created lazily; no FK, ownership or historical customer data is changed.
CREATE TABLE crm_store_admission_guard (
 tenant_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
