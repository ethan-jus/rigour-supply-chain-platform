-- 只保存迁入供应链角色的来源证据；原角色及其跨应用授权不改写。
CREATE TABLE iam_app_legacy_role_mapping (
 tenant_id BINARY(16) NOT NULL,application_id BINARY(16) NOT NULL,source_role_id BINARY(16) NOT NULL,target_role_id BINARY(16) NOT NULL,
 source_fingerprint VARCHAR(64) NOT NULL,source_evidence JSON NOT NULL,actor_id BINARY(16) NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,application_id,source_role_id),UNIQUE KEY uk_app_import_target(tenant_id,application_id,target_role_id),
 CONSTRAINT fk_app_import_target FOREIGN KEY(tenant_id,application_id,target_role_id) REFERENCES iam_app_role(tenant_id,application_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
