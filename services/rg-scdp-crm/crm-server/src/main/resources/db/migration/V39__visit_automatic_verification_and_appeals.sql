ALTER TABLE crm_visit ADD COLUMN verification_state VARCHAR(24) NOT NULL DEFAULT 'PROCESSING';
UPDATE crm_visit SET verification_state=CASE WHEN status_code='EFFECTIVE' THEN 'PASSED'
    WHEN status_code IN ('INVALID','VOID') THEN 'FAILED' ELSE 'PROCESSING' END;

CREATE TABLE crm_visit_appeal (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    store_id BIGINT NOT NULL,
    visit_id BIGINT NOT NULL,
    salesperson_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    original_state VARCHAR(24) NOT NULL,
    original_reason VARCHAR(2000) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_at DATETIME(6) NOT NULL,
    reviewed_at DATETIME(6) NULL,
    reviewer_user_id VARCHAR(64) NULL,
    decision VARCHAR(24) NULL,
    review_note VARCHAR(1000) NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_visit_appeal_request(tenant_id,request_id),
    KEY idx_visit_appeal_queue(tenant_id,status,submitted_at,id),
    KEY idx_visit_appeal_visit(tenant_id,visit_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
