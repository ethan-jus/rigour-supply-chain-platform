ALTER TABLE erp_purchase_order
    ADD COLUMN source_supplier_id VARCHAR(128) NULL AFTER source_purchase_id,
    ADD COLUMN source_warehouse_id VARCHAR(128) NULL AFTER source_supplier_id;

ALTER TABLE erp_purchase_return
    ADD COLUMN source_supplier_id VARCHAR(128) NULL AFTER source_return_id,
    ADD COLUMN source_warehouse_id VARCHAR(128) NULL AFTER source_supplier_id;
