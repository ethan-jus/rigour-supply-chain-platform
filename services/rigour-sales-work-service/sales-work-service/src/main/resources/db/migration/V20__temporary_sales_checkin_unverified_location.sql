-- 临时销售打卡定位例外留档：GPS 失败时允许提交现场记录，但必须与已验证定位明确区分。
-- 旧记录无法追溯当时是否经过现行签名定位流程，统一标记 LEGACY；新严格路径显式写 VERIFIED。
-- V12 允许历史门店目录没有坐标，因此门店 LEGACY 保留“全空或全齐”；历史提交则始终有完整坐标。

ALTER TABLE temp_sales_checkin_store
    DROP CHECK ck_temp_sales_checkin_store_geocode_status,
    ADD COLUMN location_verification_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
        AFTER location_note,
    ADD COLUMN location_failure_reason VARCHAR(64) NULL
        AFTER location_verification_status,
    ADD COLUMN location_attempt_id BINARY(16) NULL
        AFTER location_failure_reason,
    ADD CONSTRAINT ck_temp_sales_checkin_store_geocode_status
        CHECK (geocode_status IN ('PENDING', 'RESOLVED', 'KEY_MISSING', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_temp_sales_checkin_store_location_verification
        CHECK (
            (
                location_verification_status = 'LEGACY'
                AND location_failure_reason IS NULL
                AND location_attempt_id IS NULL
            )
            OR (
                location_verification_status = 'VERIFIED'
                AND longitude IS NOT NULL
                AND latitude IS NOT NULL
                AND accuracy_meters IS NOT NULL
                AND location_captured_at IS NOT NULL
                AND location_failure_reason IS NULL
                AND location_attempt_id IS NULL
            )
            OR (
                location_verification_status = 'UNVERIFIED'
                AND location_failure_reason IS NOT NULL
                AND location_failure_reason IN (
                    'PERMISSION_DENIED', 'POSITION_UNAVAILABLE', 'TIMEOUT', 'UNSUPPORTED',
                    'INSECURE_CONTEXT', 'INVALID_POSITION', 'TIMESTAMP_UNUSABLE',
                    'ACCURACY_INSUFFICIENT', 'RESOLVE_FAILED', 'USER_CONTINUED_AFTER_WAIT'
                )
                AND location_attempt_id IS NOT NULL
            )
        ),
    ADD INDEX idx_temp_sales_checkin_store_location_verification
        (tenant_id, location_verification_status, updated_at);

ALTER TABLE temp_sales_checkin_submission
    DROP CHECK ck_temp_sales_checkin_submission_coordinates,
    DROP CHECK ck_temp_sales_checkin_submission_geocode_status,
    MODIFY COLUMN longitude DECIMAL(10,7) NULL,
    MODIFY COLUMN latitude DECIMAL(10,7) NULL,
    MODIFY COLUMN accuracy_meters DECIMAL(10,2) NULL,
    MODIFY COLUMN location_captured_at DATETIME(6) NULL,
    ADD COLUMN location_verification_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
        AFTER location_note,
    ADD COLUMN location_failure_reason VARCHAR(64) NULL
        AFTER location_verification_status,
    ADD COLUMN location_attempt_id BINARY(16) NULL
        AFTER location_failure_reason,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_geocode_status
        CHECK (geocode_status IN ('PENDING', 'RESOLVED', 'KEY_MISSING', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_coordinates
        CHECK (
            (
                longitude IS NULL
                AND latitude IS NULL
                AND accuracy_meters IS NULL
                AND location_captured_at IS NULL
            )
            OR (
                longitude IS NOT NULL
                AND latitude IS NOT NULL
                AND accuracy_meters IS NOT NULL
                AND location_captured_at IS NOT NULL
                AND longitude BETWEEN -180 AND 180
                AND latitude BETWEEN -90 AND 90
                AND accuracy_meters >= 0
            )
        ),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_location_verification
        CHECK (
            (
                location_verification_status = 'LEGACY'
                AND longitude IS NOT NULL
                AND latitude IS NOT NULL
                AND accuracy_meters IS NOT NULL
                AND location_captured_at IS NOT NULL
                AND location_failure_reason IS NULL
                AND location_attempt_id IS NULL
            )
            OR (
                location_verification_status = 'VERIFIED'
                AND longitude IS NOT NULL
                AND latitude IS NOT NULL
                AND accuracy_meters IS NOT NULL
                AND location_captured_at IS NOT NULL
                AND location_failure_reason IS NULL
                AND location_attempt_id IS NULL
            )
            OR (
                location_verification_status = 'UNVERIFIED'
                AND location_failure_reason IS NOT NULL
                AND location_failure_reason IN (
                    'PERMISSION_DENIED', 'POSITION_UNAVAILABLE', 'TIMEOUT', 'UNSUPPORTED',
                    'INSECURE_CONTEXT', 'INVALID_POSITION', 'TIMESTAMP_UNUSABLE',
                    'ACCURACY_INSUFFICIENT', 'RESOLVE_FAILED', 'USER_CONTINUED_AFTER_WAIT'
                )
                AND location_attempt_id IS NOT NULL
            )
        ),
    ADD INDEX idx_temp_sales_checkin_submission_location_verification
        (tenant_id, location_verification_status, submitted_at);
