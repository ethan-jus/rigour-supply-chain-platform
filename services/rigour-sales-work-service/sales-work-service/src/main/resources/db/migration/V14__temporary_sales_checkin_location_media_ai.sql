-- 临时销售打卡增强：门店可读位置、媒体物理删除审计，以及录音转写/摘要状态。
-- 继续只使用 V12 创建的三张业务表；不改写既有迁移，所有新增列对旧镜像保持向后兼容。

ALTER TABLE temp_sales_checkin_store
    ADD COLUMN source_poi_id VARCHAR(128) NULL AFTER source_record_id,
    ADD COLUMN source_poi_name VARCHAR(256) NULL AFTER source_poi_id,
    ADD COLUMN source_poi_address VARCHAR(512) NULL AFTER source_poi_name,
    ADD COLUMN source_poi_longitude DECIMAL(10,6) NULL AFTER source_poi_address,
    ADD COLUMN source_poi_latitude DECIMAL(10,6) NULL AFTER source_poi_longitude,
    ADD COLUMN location_address VARCHAR(512) NULL AFTER location_note,
    ADD COLUMN location_formatted_address VARCHAR(512) NULL AFTER location_address,
    ADD COLUMN location_adcode VARCHAR(16) NULL AFTER location_formatted_address,
    ADD COLUMN amap_longitude DECIMAL(10,6) NULL AFTER location_adcode,
    ADD COLUMN amap_latitude DECIMAL(10,6) NULL AFTER amap_longitude,
    ADD COLUMN geocode_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER amap_latitude,
    ADD COLUMN geocode_error_code VARCHAR(64) NULL AFTER geocode_status,
    ADD COLUMN geocoded_at DATETIME(6) NULL AFTER geocode_error_code,
    ADD CONSTRAINT ck_temp_sales_checkin_store_source_poi_coordinates
        CHECK (
            (source_poi_longitude IS NULL AND source_poi_latitude IS NULL)
            OR (
                source_poi_longitude IS NOT NULL
                AND source_poi_latitude IS NOT NULL
                AND source_poi_longitude BETWEEN -180 AND 180
                AND source_poi_latitude BETWEEN -90 AND 90
            )
        ),
    ADD CONSTRAINT ck_temp_sales_checkin_store_geocode_status
        CHECK (geocode_status IN ('PENDING', 'RESOLVED', 'KEY_MISSING', 'FAILED'));

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN privacy_notice_version VARCHAR(32) NULL AFTER privacy_accepted,

    ADD COLUMN storefront_photo_deleted_at DATETIME(6) NULL AFTER storefront_photo_original_filename,
    ADD COLUMN storefront_photo_deleted_by VARCHAR(128) NULL AFTER storefront_photo_deleted_at,
    ADD COLUMN storefront_photo_deletion_reason VARCHAR(512) NULL AFTER storefront_photo_deleted_by,

    ADD COLUMN wechat_screenshot_deleted_at DATETIME(6) NULL AFTER wechat_screenshot_original_filename,
    ADD COLUMN wechat_screenshot_deleted_by VARCHAR(128) NULL AFTER wechat_screenshot_deleted_at,
    ADD COLUMN wechat_screenshot_deletion_reason VARCHAR(512) NULL AFTER wechat_screenshot_deleted_by,

    ADD COLUMN audio_deleted_at DATETIME(6) NULL AFTER audio_original_filename,
    ADD COLUMN audio_deleted_by VARCHAR(128) NULL AFTER audio_deleted_at,
    ADD COLUMN audio_deletion_reason VARCHAR(512) NULL AFTER audio_deleted_by,

    ADD COLUMN transcription_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUESTED' AFTER audio_deletion_reason,
    ADD COLUMN asr_task_id VARCHAR(64) NULL AFTER transcription_status,
    ADD COLUMN asr_request_id VARCHAR(128) NULL AFTER asr_task_id,
    ADD COLUMN transcript MEDIUMTEXT NULL AFTER asr_request_id,
    ADD COLUMN transcription_error_code VARCHAR(128) NULL AFTER transcript,
    ADD COLUMN transcription_attempts INT UNSIGNED NOT NULL DEFAULT 0 AFTER transcription_error_code,
    ADD COLUMN transcription_updated_at DATETIME(6) NULL AFTER transcription_attempts,

    ADD COLUMN summary_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUESTED' AFTER transcription_updated_at,
    ADD COLUMN summary_text TEXT NULL AFTER summary_status,
    ADD COLUMN summary_error_code VARCHAR(128) NULL AFTER summary_text,
    ADD COLUMN summary_model VARCHAR(128) NULL AFTER summary_error_code,
    ADD COLUMN summary_updated_at DATETIME(6) NULL AFTER summary_model,

    ADD CONSTRAINT ck_temp_sales_checkin_submission_transcription_status
        CHECK (transcription_status IN (
            'NOT_REQUESTED', 'PENDING', 'SUBMITTING', 'PROCESSING', 'SUCCEEDED',
            'FAILED', 'UNSUPPORTED', 'DELETED'
        )),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_summary_status
        CHECK (summary_status IN (
            'NOT_REQUESTED', 'PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'DELETED'
        )),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_media_deletion_actor
        CHECK (
            (storefront_photo_deleted_at IS NULL OR storefront_photo_deleted_by IS NOT NULL)
            AND (wechat_screenshot_deleted_at IS NULL OR wechat_screenshot_deleted_by IS NOT NULL)
            AND (audio_deleted_at IS NULL OR audio_deleted_by IS NOT NULL)
        ),
    ADD INDEX idx_temp_sales_checkin_submission_transcription
        (tenant_id, transcription_status, transcription_updated_at),
    ADD INDEX idx_temp_sales_checkin_submission_summary
        (tenant_id, summary_status, summary_updated_at);
