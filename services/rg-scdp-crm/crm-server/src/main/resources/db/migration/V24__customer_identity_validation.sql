-- Legacy duplicate names remain intact; serialize identity checks per tenant.
CREATE TABLE crm_customer_identity_guard (
    tenant_id VARCHAR(36) NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

-- Unknown historical accounts stay NULL. Deleted accounts no longer reserve a login.
ALTER TABLE crm_customer
    ADD COLUMN active_login_account VARCHAR(160)
        GENERATED ALWAYS AS (CASE WHEN deleted=0 THEN NULLIF(LOWER(TRIM(login_account)), '') ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_customer_active_login (tenant_id, active_login_account);
