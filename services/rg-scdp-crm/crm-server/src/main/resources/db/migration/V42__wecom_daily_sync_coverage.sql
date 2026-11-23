ALTER TABLE crm_wecom_sync ADD COLUMN coverage_from DATETIME(6) NULL;
UPDATE crm_wecom_sync SET coverage_from=observed_at WHERE sequence_no>0;
CREATE INDEX ix_wecom_sync_employee ON crm_wecom_sync(tenant_id,employee_id);
CREATE INDEX ix_wecom_acquisition_day ON crm_wecom_relation(tenant_id,employee_id,acquisition,added_at);
