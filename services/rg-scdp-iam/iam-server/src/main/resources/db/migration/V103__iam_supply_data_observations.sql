-- 数据决定与功能决定分别留存；同请求的域内 SQL 只旁路求值，不扩大准备阶段权限。
CREATE TABLE iam_app_data_observation (
 tenant_id BINARY(16) NOT NULL,application_id BINARY(16) NOT NULL,user_id BINARY(16) NOT NULL,
 application_version BIGINT NOT NULL,action_code VARCHAR(160) NOT NULL,domain_code VARCHAR(16) NOT NULL,record_key VARCHAR(160) NOT NULL,
 legacy_allowed BOOLEAN NOT NULL,proposed_allowed BOOLEAN NOT NULL,policy_json JSON NOT NULL,
 sample_count BIGINT NOT NULL DEFAULT 1,observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,application_id,application_version,user_id,action_code,domain_code,record_key),
 KEY ix_data_observation_time(tenant_id,application_id,observed_at),
 CONSTRAINT fk_data_observation_user FOREIGN KEY(tenant_id,user_id) REFERENCES iam_user(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
