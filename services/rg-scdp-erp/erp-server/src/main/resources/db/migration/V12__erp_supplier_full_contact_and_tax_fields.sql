-- The internal ERP portal currently requires complete supplier contact and tax data.
ALTER TABLE erp_supplier
    ADD COLUMN address VARCHAR(500) NULL AFTER area_name,
    ADD COLUMN mobile VARCHAR(80) NULL AFTER mobile_masked,
    ADD COLUMN phone VARCHAR(80) NULL AFTER phone_masked,
    ADD COLUMN email VARCHAR(200) NULL AFTER email_masked,
    ADD COLUMN taxpayer_number VARCHAR(80) NULL AFTER taxpayer_number_masked;
