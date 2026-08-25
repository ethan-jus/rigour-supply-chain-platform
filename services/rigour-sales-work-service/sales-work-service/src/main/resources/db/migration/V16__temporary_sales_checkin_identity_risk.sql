-- 临时销售打卡身份与风险事实：个人码仅保存强 KDF 摘要，IP/设备仅用于异常提示。
-- 不改写 V12-V15，不新增业务表；旧数据明确标记为 LEGACY_ANONYMOUS。

ALTER TABLE temp_sales_checkin_salesperson
    ADD COLUMN checkin_secret_hash VARCHAR(255) NULL AFTER sort_order,
    ADD COLUMN credential_version INT UNSIGNED NOT NULL DEFAULT 1 AFTER checkin_secret_hash,
    ADD COLUMN credential_updated_at DATETIME(6) NULL AFTER credential_version,
    ADD COLUMN credential_updated_by VARCHAR(128) NULL AFTER credential_updated_at,
    ADD COLUMN credential_update_reason VARCHAR(512) NULL AFTER credential_updated_by,
    ADD CONSTRAINT ck_temp_sales_checkin_salesperson_credential_version
        CHECK (credential_version >= 1);

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN identity_method VARCHAR(32) NOT NULL DEFAULT 'LEGACY_ANONYMOUS'
        AFTER privacy_notice_version,
    ADD COLUMN identity_verified_at DATETIME(6) NULL AFTER identity_method,
    ADD COLUMN credential_version INT UNSIGNED NULL AFTER identity_verified_at,
    ADD COLUMN device_token_hash CHAR(64) NULL AFTER credential_version,
    ADD COLUMN draft_ip_hash CHAR(64) NULL AFTER device_token_hash,
    ADD COLUMN draft_ip_network_hash CHAR(64) NULL AFTER draft_ip_hash,
    ADD COLUMN draft_ip_masked VARCHAR(64) NULL AFTER draft_ip_network_hash,
    ADD COLUMN submitted_ip_hash CHAR(64) NULL AFTER draft_ip_masked,
    ADD COLUMN submitted_ip_network_hash CHAR(64) NULL AFTER submitted_ip_hash,
    ADD COLUMN submitted_ip_masked VARCHAR(64) NULL AFTER submitted_ip_network_hash,
    ADD COLUMN user_agent_hash CHAR(64) NULL AFTER submitted_ip_masked,
    ADD COLUMN user_agent_summary VARCHAR(192) NULL AFTER user_agent_hash,
    ADD COLUMN risk_level VARCHAR(16) NOT NULL DEFAULT 'NONE' AFTER user_agent_summary,
    ADD COLUMN risk_flags_json JSON NULL AFTER risk_level,
    ADD COLUMN risk_evaluated_at DATETIME(6) NULL AFTER risk_flags_json,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_identity_method
        CHECK (identity_method IN ('LEGACY_ANONYMOUS', 'PERSONAL_CODE')),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_identity_version
        CHECK (credential_version IS NULL OR credential_version >= 1),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_risk_level
        CHECK (risk_level IN ('NONE', 'LOW', 'MEDIUM', 'HIGH')),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_identity_hashes
        CHECK (
            (device_token_hash IS NULL OR device_token_hash REGEXP '^[0-9a-f]{64}$')
            AND (draft_ip_hash IS NULL OR draft_ip_hash REGEXP '^[0-9a-f]{64}$')
            AND (draft_ip_network_hash IS NULL OR draft_ip_network_hash REGEXP '^[0-9a-f]{64}$')
            AND (submitted_ip_hash IS NULL OR submitted_ip_hash REGEXP '^[0-9a-f]{64}$')
            AND (submitted_ip_network_hash IS NULL OR submitted_ip_network_hash REGEXP '^[0-9a-f]{64}$')
            AND (user_agent_hash IS NULL OR user_agent_hash REGEXP '^[0-9a-f]{64}$')
        ),
    ADD INDEX idx_temp_sales_checkin_submission_device_risk
        (tenant_id, device_token_hash, status, submitted_at),
    ADD INDEX idx_temp_sales_checkin_submission_sales_risk
        (tenant_id, salesperson_id, status, submitted_at),
    ADD INDEX idx_temp_sales_checkin_submission_ip_risk
        (tenant_id, submitted_ip_network_hash, status, submitted_at);
