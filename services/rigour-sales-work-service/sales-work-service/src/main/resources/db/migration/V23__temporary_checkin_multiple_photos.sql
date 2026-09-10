-- 每张现场照片独立标识；原 storefront_photo_* 列继续投影首张活动照片，兼容已发布客户端。
-- 旧文件没有可核验的拍摄来源或独立上传时间，迁移保持 NULL，不补造事实。
CREATE TABLE temp_sales_checkin_photo (
    tenant_id BINARY(16) NOT NULL,
    submission_id BINARY(16) NOT NULL,
    photo_id BINARY(16) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    uploaded_at DATETIME(6) NULL,
    capture_source VARCHAR(24) NULL,
    deleted_at DATETIME(6) NULL,
    deleted_by VARCHAR(128) NULL,
    deletion_reason VARCHAR(512) NULL,
    PRIMARY KEY (tenant_id, submission_id, photo_id),
    CONSTRAINT fk_temp_photo_submission FOREIGN KEY (tenant_id, submission_id)
        REFERENCES temp_sales_checkin_submission (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_temp_photo_size CHECK (size_bytes > 0),
    CONSTRAINT ck_temp_photo_source CHECK (capture_source IS NULL OR capture_source IN ('CAMERA','FILE_IMPORT'))
);

INSERT INTO temp_sales_checkin_photo
    (tenant_id, submission_id, photo_id, object_key, content_type, size_bytes, sha256,
     original_filename, deleted_at, deleted_by, deletion_reason)
SELECT tenant_id, id, id, storefront_photo_object_key, storefront_photo_content_type,
       storefront_photo_size_bytes, storefront_photo_sha256, storefront_photo_original_filename,
       storefront_photo_deleted_at, storefront_photo_deleted_by, storefront_photo_deletion_reason
  FROM temp_sales_checkin_submission
 WHERE storefront_photo_object_key IS NOT NULL;

ALTER TABLE temp_sales_checkin_submission
    DROP CHECK ck_temp_sales_checkin_submission_privacy,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_privacy CHECK (privacy_accepted IN (0, 1));
