-- Sales 保存采集结果和 outbox，CRM 保存观察快照及统一门店投影。
ALTER TABLE crm_store_master ADD business_type_code VARCHAR(50) NULL;
CREATE TABLE crm_visit_fieldwork (
 tenant_id VARCHAR(64) NOT NULL,visit_id BIGINT NOT NULL,store_id BIGINT NOT NULL,
 ownership_id BIGINT NOT NULL,accepted_json JSON NOT NULL,sync_status VARCHAR(24) NOT NULL,
 submitted_at DATETIME(6) NOT NULL,applied_at DATETIME(6) NULL,
 PRIMARY KEY(tenant_id,visit_id), KEY ix_fieldwork_store(tenant_id,store_id,submitted_at),
 FOREIGN KEY(tenant_id,store_id) REFERENCES crm_store_master(tenant_id,id),
 CHECK(sync_status IN ('APPLIED','CONFLICT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE crm_store_business_progress (
 tenant_id VARCHAR(64) NOT NULL,store_id BIGINT NOT NULL,business_line_code VARCHAR(50) NOT NULL,
 intention_level VARCHAR(50) NULL,cooperation_status VARCHAR(50) NULL,
 source_visit_id BIGINT NOT NULL,observed_at DATETIME(6) NOT NULL,actor_user_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,store_id,business_line_code),
 FOREIGN KEY(tenant_id,store_id) REFERENCES crm_store_master(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
