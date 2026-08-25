-- 临时销售打卡多段录音：继续把媒体元数据保存在提交表中，不新增媒体业务表。
-- 旧 audio_* 列保留为首个活动录音的兼容投影；历史单录音以 submission id 作为稳定段 ID 回填。

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN audio_segments_json JSON NULL AFTER audio_deletion_reason,
    ADD COLUMN audio_active_segment_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER audio_segments_json,
    ADD COLUMN audio_active_size_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER audio_active_segment_count;

UPDATE temp_sales_checkin_submission
   SET audio_segments_json = CASE
           WHEN audio_object_key IS NULL THEN JSON_ARRAY()
           ELSE JSON_ARRAY(JSON_OBJECT(
               'segmentId', LOWER(CONCAT(
                   SUBSTR(HEX(id), 1, 8), '-', SUBSTR(HEX(id), 9, 4), '-',
                   SUBSTR(HEX(id), 13, 4), '-', SUBSTR(HEX(id), 17, 4), '-', SUBSTR(HEX(id), 21, 12)
               )),
               'objectKey', audio_object_key,
               'contentType', audio_content_type,
               'sizeBytes', audio_size_bytes,
               'sha256', audio_sha256,
               'originalFilename', audio_original_filename,
               'uploadedAt', DATE_FORMAT(COALESCE(submitted_at, updated_at, created_at),
                                         '%Y-%m-%dT%H:%i:%s.%fZ'),
               'deletedAt', IF(audio_deleted_at IS NULL, NULL,
                   DATE_FORMAT(audio_deleted_at, '%Y-%m-%dT%H:%i:%s.%fZ')),
               'deletedBy', audio_deleted_by,
               'deletionReason', audio_deletion_reason
           ))
       END,
       audio_active_segment_count = IF(
           audio_object_key IS NOT NULL AND audio_deleted_at IS NULL, 1, 0),
       audio_active_size_bytes = IF(
           audio_object_key IS NOT NULL AND audio_deleted_at IS NULL,
           COALESCE(audio_size_bytes, 0), 0);

ALTER TABLE temp_sales_checkin_submission
    MODIFY COLUMN audio_segments_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_audio_segments_json
        CHECK (JSON_TYPE(audio_segments_json) = 'ARRAY'),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_audio_segment_count
        CHECK (audio_active_segment_count <= JSON_LENGTH(audio_segments_json)),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_audio_segment_bytes
        CHECK (
            (audio_active_segment_count = 0 AND audio_active_size_bytes = 0)
            OR (audio_active_segment_count > 0 AND audio_active_size_bytes > 0)
        );
