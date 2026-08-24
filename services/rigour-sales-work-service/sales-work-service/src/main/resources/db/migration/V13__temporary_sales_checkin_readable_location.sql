-- 临时销售打卡可读位置：保留浏览器原始 GPS 坐标，同时保存转换后的高德坐标和逆地理编码快照。
-- 既有记录保持 PENDING，可在高德 Web 服务 Key 配置后补解析；不改写 V12 历史。

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN location_address VARCHAR(512) NULL AFTER location_note,
    ADD COLUMN location_formatted_address VARCHAR(512) NULL AFTER location_address,
    ADD COLUMN location_adcode VARCHAR(16) NULL AFTER location_formatted_address,
    ADD COLUMN location_province VARCHAR(64) NULL AFTER location_adcode,
    ADD COLUMN location_city VARCHAR(64) NULL AFTER location_province,
    ADD COLUMN location_district VARCHAR(64) NULL AFTER location_city,
    ADD COLUMN location_township VARCHAR(128) NULL AFTER location_district,
    ADD COLUMN amap_longitude DECIMAL(10,6) NULL AFTER location_township,
    ADD COLUMN amap_latitude DECIMAL(10,6) NULL AFTER amap_longitude,
    ADD COLUMN geocode_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER amap_latitude,
    ADD COLUMN geocode_error_code VARCHAR(64) NULL AFTER geocode_status,
    ADD COLUMN geocoded_at DATETIME(6) NULL AFTER geocode_error_code,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_geocode_status
        CHECK (geocode_status IN ('PENDING', 'RESOLVED', 'KEY_MISSING', 'FAILED')),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_amap_coordinates
        CHECK (
            (amap_longitude IS NULL AND amap_latitude IS NULL)
            OR (
                amap_longitude IS NOT NULL
                AND amap_latitude IS NOT NULL
                AND amap_longitude BETWEEN -180 AND 180
                AND amap_latitude BETWEEN -90 AND 90
            )
        ),
    ADD INDEX idx_temp_sales_checkin_submission_city_created
        (tenant_id, city, created_at);
