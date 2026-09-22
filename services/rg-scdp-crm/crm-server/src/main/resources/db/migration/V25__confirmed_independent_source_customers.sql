ALTER TABLE crm_source_binding
    ADD COLUMN independent_confirmed TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN unresolved_owner_allowed TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN independence_evidence VARCHAR(1000) NULL,
    ADD COLUMN independence_actor VARCHAR(64) NULL,
    ADD COLUMN independence_previous_target_id BINARY(16) NULL,
    ADD COLUMN independence_confirmed_at DATETIME(6) NULL;
