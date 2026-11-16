-- Technical leads and human review are independent from CRM visit effectiveness.
CREATE TABLE sales_fieldwork_case (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    sales_profile_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NULL,
    evidence_key VARCHAR(128) NOT NULL,
    kind VARCHAR(48) NOT NULL,
    title VARCHAR(200) NOT NULL,
    evidence_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    detected_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fieldwork_case_evidence (tenant_id, evidence_key),
    KEY ix_fieldwork_case_day (tenant_id, work_day_id, status),
    KEY ix_fieldwork_case_visit (tenant_id, visit_id),
    KEY ix_fieldwork_case_owner (tenant_id, sales_profile_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE sales_fieldwork_case_event (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    case_id BINARY(16) NOT NULL,
    actor_user_id BINARY(16) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NULL,
    note TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fieldwork_case_command (tenant_id, case_id, actor_user_id, request_id),
    KEY ix_fieldwork_case_history (tenant_id, case_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
