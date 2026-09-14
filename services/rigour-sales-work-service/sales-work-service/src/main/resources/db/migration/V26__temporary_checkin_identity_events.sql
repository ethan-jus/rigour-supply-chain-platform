-- 只追加新版真实成功验证事件，不从历史拜访回填登录轨迹，不保存 Cookie、会话或个人码。
CREATE TABLE temp_sales_checkin_identity_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    device_token_hash CHAR(64) NOT NULL,
    salesperson_id BINARY(16) NOT NULL,
    salesperson_name_snapshot VARCHAR(128) NOT NULL,
    identity_city VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_temp_identity_event(tenant_id,event_id),
    KEY idx_temp_identity_event_device(tenant_id,device_token_hash,occurred_at,id),
    KEY idx_temp_identity_event_city(tenant_id,device_token_hash,identity_city,occurred_at,id),
    CONSTRAINT ck_temp_identity_event_type CHECK(event_type='PERSONAL_CODE_VERIFIED'),
    CONSTRAINT ck_temp_identity_event_device CHECK(device_token_hash REGEXP '^[0-9a-f]{64}$')
);
