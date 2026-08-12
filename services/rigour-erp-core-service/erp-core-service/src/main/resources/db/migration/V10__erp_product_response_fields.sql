ALTER TABLE erp_product_spu
    ADD COLUMN source_category_id VARCHAR(128) NULL AFTER source_multi_id,
    ADD COLUMN source_brand_id VARCHAR(128) NULL AFTER source_category_id,
    ADD COLUMN conversion_barcode VARCHAR(128) NULL AFTER source_brand_id;

ALTER TABLE erp_product_sku
    ADD COLUMN first_specification_value_source_id VARCHAR(128) NULL AFTER source_options_id,
    ADD COLUMN second_specification_value_source_id VARCHAR(128) NULL AFTER first_specification_value_source_id;
