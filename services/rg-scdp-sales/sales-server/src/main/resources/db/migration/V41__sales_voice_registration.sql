CREATE TABLE sales_voice_profile (
    tenant_id BINARY(16) NOT NULL,
    sales_profile_id BINARY(16) NOT NULL,
    employee_name VARCHAR(128) NOT NULL,
    embedding_json JSON NULL,
    model VARCHAR(128) NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    consented_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id,sales_profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE sales_visit_speaker_context
    ADD COLUMN speaker_names_json JSON NULL,
    ADD COLUMN voice_registry_version BIGINT UNSIGNED NULL;

-- Reassess existing completed captures using their saved evidence and policy snapshot.
UPDATE sales_visit SET crm_last_enqueued_at=NULL WHERE status='CHECKED_OUT' AND crm_visit_id IS NOT NULL;
