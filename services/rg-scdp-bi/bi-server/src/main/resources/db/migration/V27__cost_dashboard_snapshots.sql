-- 新成本看板：三域原始单据标准化为一条费用事实，不复用旧城市成本表。
CREATE TABLE bi_cost_snapshot (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL,
 generated_at DATETIME(6) NOT NULL, effective_cutoff DATE NOT NULL,
 versions_json JSON NOT NULL, cities_json JSON NOT NULL, row_count BIGINT NOT NULL,
 PRIMARY KEY(id), KEY idx_cost_snapshot_tenant_time(tenant_id,generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE bi_source_cost_record (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL, snapshot_id VARCHAR(36) NOT NULL,
 source_service VARCHAR(16) NOT NULL, source_id VARCHAR(36) NOT NULL, cost_date DATE,
 category VARCHAR(32) NOT NULL, record_json JSON NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_cost_fact(tenant_id,snapshot_id,source_service,source_id),
 KEY idx_cost_fact_period(tenant_id,snapshot_id,cost_date,category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE bi_cost_refresh_state (
 tenant_id VARCHAR(36) NOT NULL, snapshot_id VARCHAR(36), attempted_at DATETIME(6), succeeded_at DATETIME(6),
 status VARCHAR(16) NOT NULL, error_code VARCHAR(64), PRIMARY KEY(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE bi_cost_export (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL, actor_id VARCHAR(36) NOT NULL,
 snapshot_id VARCHAR(36) NOT NULL, authorization_hash VARCHAR(64) NOT NULL,
 filter_json JSON NOT NULL, status VARCHAR(16) NOT NULL, error_code VARCHAR(64), content LONGTEXT,
 row_count BIGINT, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(id), KEY idx_cost_export_owner(tenant_id,actor_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
