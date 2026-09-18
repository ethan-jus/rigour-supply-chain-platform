ALTER TABLE crm_responsibility_source_conflict
 ADD resolved_by VARCHAR(64) NULL, ADD resolved_at DATETIME(6) NULL,
 ADD resolution_reason VARCHAR(500) NULL, ADD customer_revision BIGINT NULL;
