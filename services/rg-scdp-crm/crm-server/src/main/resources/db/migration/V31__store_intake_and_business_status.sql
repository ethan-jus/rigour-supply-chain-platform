-- Business operation is independent of archive validity, ownership and cooperation history.
ALTER TABLE crm_store_master
 ADD COLUMN business_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
 ADD COLUMN business_status_reason VARCHAR(500) NULL,
 ADD COLUMN dedup_key BINARY(32) NULL,
 ADD KEY ix_store_dedup(tenant_id,dedup_key),
 ADD CONSTRAINT ck_store_business_status CHECK(business_status IN ('UNKNOWN','OPEN','TEMP_CLOSED','CLOSED'));
ALTER TABLE crm_store_summary
 ADD COLUMN business_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
 ADD KEY ix_summary_business(tenant_id,business_status,pool_type,record_status,store_id);
UPDATE crm_store_master SET business_status='CLOSED',business_status_reason=status_reason WHERE record_status='CLOSED';
UPDATE crm_store_summary q JOIN crm_store_master s ON s.tenant_id=q.tenant_id AND s.id=q.store_id SET q.business_status=s.business_status;
UPDATE crm_store_master SET dedup_key=UNHEX(SHA2(CONCAT_WS('|',admin_area_code,REGEXP_REPLACE(LOWER(TRIM(REPLACE(REPLACE(name,'（','('),'）',')'))), '[[:space:]　]', ''),REGEXP_REPLACE(LOWER(TRIM(REPLACE(REPLACE(address,'（','('),'）',')'))), '[[:space:]　]', '')),256));
-- Keep existing records intact, including any historical duplicates; admissions serialize the
-- indexed check with the tenant policy lock, and POI identity has its existing unique constraint.
ALTER TABLE crm_store_lead
 MODIFY submitter_employee_id BIGINT NULL,
 ADD COLUMN proposed_store_type_code VARCHAR(32) NULL,
 ADD KEY ix_lead_creator(tenant_id,created_by,submitted_at,id);
