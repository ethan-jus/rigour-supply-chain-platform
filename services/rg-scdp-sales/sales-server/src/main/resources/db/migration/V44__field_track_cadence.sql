ALTER TABLE sales_field_policy_version
    ADD COLUMN location_sample_interval_seconds INT NOT NULL DEFAULT 5
        COMMENT '移动时定位点保存间隔（秒）',
    ADD COLUMN location_upload_interval_seconds INT NOT NULL DEFAULT 60
        COMMENT '定位点批量上传间隔（秒）';

-- Preserve non-default recording intervals while adopting the new five-second default.
UPDATE sales_field_policy_version
SET location_sample_interval_seconds = location_interval_minutes * 60
WHERE location_interval_minutes <> 1;
