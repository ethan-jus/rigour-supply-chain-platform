-- 准备阶段的真实请求功能决定对比；不会参与放行。范围快照供审查，不冒充业务行过滤结果。
CREATE TABLE iam_app_authorization_observation (
 tenant_id BINARY(16) NOT NULL,application_id BINARY(16) NOT NULL,user_id BINARY(16) NOT NULL,
 application_version BIGINT NOT NULL,action_code VARCHAR(128) NOT NULL,legacy_action_code VARCHAR(128) NOT NULL,
 legacy_allowed BOOLEAN NOT NULL,proposed_allowed BOOLEAN NOT NULL,policy_json JSON NOT NULL,
 sample_count BIGINT NOT NULL DEFAULT 1,observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,application_id,application_version,user_id,action_code,legacy_action_code),
 KEY ix_app_observation_time(tenant_id,application_id,observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
