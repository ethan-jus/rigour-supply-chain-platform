-- 单次拜访共享匿名说话人上下文，不用于跨拜访身份识别，不下发前端。
CREATE TABLE sales_visit_speaker_context (
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    profiles_json JSON NOT NULL,
    model VARCHAR(128) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, visit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO sales_visit_speaker_context (tenant_id,visit_id,profiles_json,updated_at)
SELECT DISTINCT tenant_id,visit_id,JSON_ARRAY(),UTC_TIMESTAMP(6) FROM sales_recording_analysis;

-- 已授权 DEV 现有录音补充说话人。复用原任务/原结果行，不生成手机或后台副本。
UPDATE sales_recording_analysis a JOIN sales_visit v ON v.tenant_id=a.tenant_id AND v.id=a.visit_id
SET a.status='PENDING',a.attempts=0,a.error_code=NULL,a.lease_id=NULL,a.lease_until=NULL,
    a.next_attempt_at=UTC_TIMESTAMP(6),a.updated_at=UTC_TIMESTAMP(6)
WHERE a.status='SUCCEEDED' AND v.status='CHECKED_OUT';
