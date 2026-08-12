-- Supplier data is now stored and returned only in complete form for the internal portal.
ALTER TABLE erp_supplier
    DROP COLUMN address_masked,
    DROP COLUMN mobile_masked,
    DROP COLUMN phone_masked,
    DROP COLUMN email_masked,
    DROP COLUMN bank_account_last4,
    DROP COLUMN taxpayer_number_masked;
