ALTER TABLE erp_master_source_binding
    ADD COLUMN source_presence VARCHAR(24) NOT NULL DEFAULT 'PRESENT' AFTER source_payload_hash,
    ADD COLUMN source_absent_at DATETIME(6) NULL AFTER source_presence,
    ADD KEY idx_erp_source_presence (
        tenant_id, source_system, source_object_type, source_presence, source_absent_at
    );
