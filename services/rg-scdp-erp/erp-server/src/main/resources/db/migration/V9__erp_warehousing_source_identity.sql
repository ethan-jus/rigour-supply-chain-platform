ALTER TABLE erp_warehousing_receipt
    ADD COLUMN source_warehouse_id VARCHAR(128) NULL AFTER source_warehousing_id,
    ADD COLUMN source_supplier_id VARCHAR(128) NULL AFTER source_warehouse_id;
