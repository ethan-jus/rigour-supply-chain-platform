-- 地址是对已保存设备坐标的派生说明；不改原始位置、采样时刻、质量和风险。
ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN address_source VARCHAR(64) NULL,
    ADD COLUMN address_resolved_at DATETIME(6) NULL,
    ADD COLUMN address_conversion_version VARCHAR(64) NULL,
    ADD COLUMN address_coordinate_hash CHAR(64) NULL;

-- 旧 geocoded_at 可能是草稿落库时间，不能补造精确解析时间。
UPDATE temp_sales_checkin_submission
   SET address_source='LEGACY_SNAPSHOT', address_conversion_version='UNRECORDED'
 WHERE NULLIF(TRIM(location_address),'') IS NOT NULL;

CREATE TABLE temp_sales_checkin_address_cache (
    tenant_id BINARY(16) NOT NULL,
    coordinate_hash CHAR(64) NOT NULL,
    provider_version VARCHAR(96) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_json JSON NULL,
    resolved_at DATETIME(6) NULL,
    retry_at DATETIME(6) NULL,
    claim_token BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    requested_by VARCHAR(128) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id,coordinate_hash,provider_version),
    CONSTRAINT ck_temp_address_cache_status CHECK (status IN ('PROCESSING','RESOLVED','FAILED'))
);

-- 每租户最多一个按需解析在途；配额跨重启保留。只对显式管理员操作分配额度。
CREATE TABLE temp_sales_checkin_address_guard (
    tenant_id BINARY(16) NOT NULL PRIMARY KEY,
    budget_date DATE NOT NULL,
    calls_used INT UNSIGNED NOT NULL DEFAULT 0,
    lease_token BINARY(16) NULL,
    lease_until DATETIME(6) NULL
);
