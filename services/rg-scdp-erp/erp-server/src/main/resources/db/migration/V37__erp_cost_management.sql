-- 成本独立归集；租金押金单列，不计入费用。来源快照仅供追溯，不回写业务单据。
CREATE TABLE erp_cost_entry (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, document_no VARCHAR(50) NOT NULL,
 request_id VARCHAR(36) NOT NULL, category_code VARCHAR(20) NOT NULL,
 title VARCHAR(200) NOT NULL, type_code VARCHAR(64) NULL, city_code VARCHAR(64) NULL,
 department_id BIGINT NULL, owner_employee_code VARCHAR(50) NULL,
 warehouse_id BIGINT NULL, source_warehouse_id BIGINT NULL,
 cost_date DATE NOT NULL, amount DECIMAL(18,2) NULL, payment_status_code VARCHAR(32) NOT NULL,
 status_code VARCHAR(20) NOT NULL DEFAULT 'DRAFT', payer_code VARCHAR(32) NULL,
 data_json JSON NOT NULL, sources_json JSON NOT NULL, warehouse_name VARCHAR(120) NULL,
 owner_employee_name VARCHAR(120) NULL, revision INT NOT NULL DEFAULT 1,
 created_by VARCHAR(50) NOT NULL, updated_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_cost_request(tenant_id,request_id),
 UNIQUE KEY uk_cost_no(tenant_id,document_no), KEY ix_cost_query(tenant_id,category_code,cost_date,status_code),
 KEY ix_cost_warehouse(tenant_id,warehouse_id), KEY ix_cost_department(tenant_id,department_id),
 CONSTRAINT ck_cost_category CHECK(category_code IN ('MARKETING','RENTAL')),
 CONSTRAINT ck_cost_status CHECK(status_code IN ('DRAFT','CONFIRMED','VOID')),
 CONSTRAINT ck_cost_amount CHECK(amount IS NULL OR amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE erp_cost_audit (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, entry_id VARCHAR(36) NOT NULL,
 action_code VARCHAR(32) NOT NULL, actor_id VARCHAR(50) NOT NULL, reason VARCHAR(500) NULL,
 snapshot_json JSON NOT NULL, created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), KEY ix_cost_audit(tenant_id,entry_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE erp_cost_guard (tenant_id VARCHAR(64) NOT NULL,version BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(tenant_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
