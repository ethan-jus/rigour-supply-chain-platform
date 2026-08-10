-- Sales Work V4：为飞书录音片段补充客户端幂等标识。
-- tt.uploadFile 在网络超时后可能由用户重试；同一拜访录音片段必须只登记一次。

ALTER TABLE sales_recording_clip
    ADD COLUMN client_clip_id VARCHAR(128) NULL COMMENT '客户端片段幂等标识' AFTER clip_index;

UPDATE sales_recording_clip
   SET client_clip_id = CONCAT('legacy-', LOWER(HEX(id)))
 WHERE client_clip_id IS NULL;

ALTER TABLE sales_recording_clip
    MODIFY COLUMN client_clip_id VARCHAR(128) NOT NULL COMMENT '客户端片段幂等标识',
    ADD CONSTRAINT uk_sales_recording_client_clip
        UNIQUE (tenant_id, recording_session_id, client_clip_id);
