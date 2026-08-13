-- Add complete contact columns without renaming old columns so rolling deployments remain compatible.
ALTER TABLE erp_warehouse
    ADD COLUMN phone VARCHAR(80) NULL AFTER phone_masked;

UPDATE erp_warehouse
SET phone = phone_masked
WHERE phone IS NULL AND phone_masked IS NOT NULL;

ALTER TABLE erp_purchase_return
    ADD COLUMN contact_phone VARCHAR(80) NULL AFTER contact_phone_masked,
    ADD COLUMN contact_address VARCHAR(500) NULL AFTER contact_address_masked;

UPDATE erp_purchase_return
SET contact_phone = contact_phone_masked,
    contact_address = contact_address_masked
WHERE (contact_phone IS NULL AND contact_phone_masked IS NOT NULL)
   OR (contact_address IS NULL AND contact_address_masked IS NOT NULL);
