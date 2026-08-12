-- getGoodsList 的最低订量属于商品经营字段，落入 ERP 当前商品模型。

ALTER TABLE erp_product_spu
    ADD COLUMN minimum_order DECIMAL(20,6) NULL COMMENT '订货宝最低订货量' AFTER default_barcode,
    ADD COLUMN minimum_order_unit VARCHAR(32) NULL COMMENT '订货宝最低订货量单位原值' AFTER minimum_order;

-- 让已同步商品在下一次同步时重新应用新增字段；来源 ID 与 ERP 主键保持不变。
UPDATE erp_master_source_binding
SET source_payload_hash = SHA2(CONCAT(source_payload_hash, ':ERP_PRODUCT_V5'), 256),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE source_system = 'DINGHUOBAO'
  AND source_object_type = 'PRODUCT_SPU';
