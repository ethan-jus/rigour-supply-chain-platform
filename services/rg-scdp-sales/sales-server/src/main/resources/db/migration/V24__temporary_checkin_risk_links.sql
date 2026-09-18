-- 关联证据始终来自现存已提交记录；编号登记不是媒体副本，不参与公开上传或提交事务。
CREATE TABLE temp_sales_checkin_risk_device (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    hash_version VARCHAR(16) NOT NULL DEFAULT 'V1',
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_temp_risk_device (tenant_id,token_hash,hash_version)
);
CREATE TABLE temp_sales_checkin_risk_audio (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_temp_risk_audio (tenant_id,sha256,size_bytes),
    CONSTRAINT ck_temp_risk_audio_size CHECK (size_bytes>0)
);

-- JSON 清单为唯一事实源；旧首段投影只在清单为空时兜底，避免重复计数。
CREATE VIEW temp_sales_checkin_risk_audio_source AS
SELECT s.tenant_id,s.id AS submission_id,LOWER(j.segment_id) AS segment_id,
       LOWER(j.sha256) AS sha256,j.size_bytes,j.original_filename,j.content_type,
       j.uploaded_at_text,j.client_duration_ms,j.capture_source
FROM temp_sales_checkin_submission s
JOIN JSON_TABLE(s.audio_segments_json,'$[*]' COLUMNS (
    segment_id VARCHAR(64) PATH '$.segmentId' NULL ON EMPTY,
    sha256 VARCHAR(64) PATH '$.sha256' NULL ON EMPTY,
    size_bytes BIGINT PATH '$.sizeBytes' NULL ON EMPTY,
    object_key VARCHAR(1024) PATH '$.objectKey' NULL ON EMPTY,
    deleted_at VARCHAR(64) PATH '$.deletedAt' NULL ON EMPTY,
    original_filename VARCHAR(512) PATH '$.originalFilename' NULL ON EMPTY,
    content_type VARCHAR(128) PATH '$.contentType' NULL ON EMPTY,
    uploaded_at_text VARCHAR(64) PATH '$.uploadedAt' NULL ON EMPTY,
    client_duration_ms BIGINT PATH '$.clientDurationMs' NULL ON EMPTY,
    capture_source VARCHAR(32) PATH '$.captureSource' NULL ON EMPTY)) j
WHERE s.status='SUBMITTED' AND s.deletion_state='NONE' AND j.deleted_at IS NULL
  AND j.object_key IS NOT NULL AND j.object_key<>'' AND j.size_bytes>0
  AND j.sha256 REGEXP '^[0-9a-fA-F]{64}$'
  AND j.segment_id REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
UNION ALL
SELECT s.tenant_id,s.id,LOWER(BIN_TO_UUID(s.id)),s.audio_sha256,s.audio_size_bytes,
       s.audio_original_filename,s.audio_content_type,NULL,NULL,NULL
FROM temp_sales_checkin_submission s
WHERE s.status='SUBMITTED' AND s.deletion_state='NONE'
  AND (s.audio_segments_json IS NULL OR JSON_LENGTH(s.audio_segments_json)=0)
  AND s.audio_deleted_at IS NULL AND s.audio_object_key IS NOT NULL AND s.audio_object_key<>''
  AND s.audio_size_bytes>0 AND s.audio_sha256 REGEXP '^[0-9a-f]{64}$';

INSERT INTO temp_sales_checkin_risk_device(tenant_id,token_hash,created_at)
SELECT tenant_id,device_token_hash,UTC_TIMESTAMP(6) FROM temp_sales_checkin_submission
WHERE status='SUBMITTED' AND deletion_state='NONE' AND device_token_hash IS NOT NULL
GROUP BY tenant_id,device_token_hash ORDER BY tenant_id,device_token_hash;
INSERT INTO temp_sales_checkin_risk_audio(tenant_id,sha256,size_bytes,created_at)
SELECT tenant_id,sha256,size_bytes,UTC_TIMESTAMP(6) FROM temp_sales_checkin_risk_audio_source
GROUP BY tenant_id,sha256,size_bytes ORDER BY tenant_id,sha256,size_bytes;

CREATE TABLE temp_sales_checkin_risk_assignment (
    id BINARY(16) NOT NULL PRIMARY KEY,tenant_id BINARY(16) NOT NULL,device_id BIGINT UNSIGNED NOT NULL,
    client_event_id BINARY(16) NOT NULL,scope_key VARCHAR(80) NOT NULL,
    assignment_type VARCHAR(24) NOT NULL,salesperson_id BINARY(16) NULL,salesperson_name VARCHAR(128) NULL,
    valid_from DATE NULL,valid_to DATE NULL,note VARCHAR(2000) NOT NULL,actor VARCHAR(128) NOT NULL,
    assigned_at DATETIME(6) NOT NULL,request_hash CHAR(64) NOT NULL,
    UNIQUE KEY uk_temp_risk_assignment_event(tenant_id,client_event_id),
    KEY idx_temp_risk_assignment_device(tenant_id,device_id,assigned_at),
    CONSTRAINT ck_temp_risk_assignment_type CHECK(assignment_type IN ('PERSONAL','SHARED','UNCONFIRMED')),
    CONSTRAINT ck_temp_risk_assignment_range CHECK(valid_from IS NULL OR valid_to IS NULL OR valid_from<=valid_to)
);
CREATE TABLE temp_sales_checkin_risk_review (
    id BINARY(16) NOT NULL PRIMARY KEY,tenant_id BINARY(16) NOT NULL,
    group_kind VARCHAR(16) NOT NULL,group_id BIGINT UNSIGNED NOT NULL,scope_key VARCHAR(80) NOT NULL,
    client_event_id BINARY(16) NOT NULL,evidence_version CHAR(64) NOT NULL,rules_version VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,note VARCHAR(2000) NOT NULL,actor VARCHAR(128) NOT NULL,
    reviewed_at DATETIME(6) NOT NULL,member_count BIGINT NOT NULL,request_hash CHAR(64) NOT NULL,
    UNIQUE KEY uk_temp_risk_review_event(tenant_id,client_event_id),
    KEY idx_temp_risk_review_group(tenant_id,group_kind,group_id,scope_key,reviewed_at),
    CONSTRAINT ck_temp_risk_review_kind CHECK(group_kind IN ('DEVICE','AUDIO')),
    CONSTRAINT ck_temp_risk_review_status CHECK(status IN ('EXPLAINED','FLAGGED','INCONCLUSIVE'))
);
CREATE TABLE temp_sales_checkin_risk_review_member (
    tenant_id BINARY(16) NOT NULL,review_id BINARY(16) NOT NULL,member_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant_id,review_id,member_key),
    CONSTRAINT fk_temp_risk_review_member FOREIGN KEY(review_id)
        REFERENCES temp_sales_checkin_risk_review(id) ON DELETE CASCADE
);
