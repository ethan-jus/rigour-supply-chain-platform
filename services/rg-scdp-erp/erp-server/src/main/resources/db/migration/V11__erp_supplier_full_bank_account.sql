-- ERP supplier bank account is currently required in full by the internal portal.
ALTER TABLE erp_supplier
    ADD COLUMN bank_account VARCHAR(200) NULL AFTER bank_name;
