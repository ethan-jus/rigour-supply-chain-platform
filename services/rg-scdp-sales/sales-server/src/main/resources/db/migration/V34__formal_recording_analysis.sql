-- Analysis references original clips; no duplicate audio or CRM visit master.
CREATE TABLE sales_recording_analysis (
    tenant_id BINARY(16) NOT NULL,
    clip_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    asr_task_id VARCHAR(128) NULL,
    provider_request_id VARCHAR(128) NULL,
    transcript LONGTEXT NULL,
    segments_json JSON NULL,
    summary_status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
    summary TEXT NULL,
    summary_model VARCHAR(128) NULL,
    error_code VARCHAR(64) NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_id BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, clip_id),
    KEY ix_recording_analysis_due (status, next_attempt_at, lease_until),
    KEY ix_recording_analysis_visit (tenant_id, visit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
