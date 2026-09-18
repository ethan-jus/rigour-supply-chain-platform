-- 临时销售打卡治理：为少量测试数据的受控物理删除保留独立任务审计。
-- 业务记录最终会被硬删除，因此任务表必须独立保留请求人、原因和逐条结果。

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN deletion_state VARCHAR(24) NOT NULL DEFAULT 'NONE' AFTER summary_updated_at,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_deletion_state
        CHECK (deletion_state IN ('NONE', 'PENDING', 'FAILED')),
    ADD INDEX idx_temp_sales_checkin_submission_deletion
        (tenant_id, deletion_state, updated_at);

CREATE TABLE temp_sales_checkin_deletion_job (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    request_id BINARY(16) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    requested_scope_city VARCHAR(64) NULL,
    reason VARCHAR(512) NOT NULL,
    requested_count INT UNSIGNED NOT NULL,
    deleted_count INT UNSIGNED NOT NULL DEFAULT 0,
    failed_count INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    result_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    CONSTRAINT pk_temp_sales_checkin_deletion_job PRIMARY KEY (id),
    CONSTRAINT uk_temp_sales_checkin_deletion_job_request
        UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_temp_sales_checkin_deletion_job_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    CONSTRAINT ck_temp_sales_checkin_deletion_job_counts
        CHECK (
            requested_count > 0
            AND deleted_count + failed_count <= requested_count
        ),
    INDEX idx_temp_sales_checkin_deletion_job_created
        (tenant_id, created_at),
    INDEX idx_temp_sales_checkin_deletion_job_status
        (tenant_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='临时打卡物理删除任务与不可变请求审计';
