-- ERP SKU 规格摘要是展示文本，不是 JSON；结构化规格继续由规格及规格值关系表表达。
-- V1 已在共享环境执行，因此通过增量迁移修正字段类型和名称。

ALTER TABLE erp_product_sku
    MODIFY COLUMN specification_summary_json VARCHAR(500) NULL
        COMMENT '展示用规格组合名称，结构化规格以关系表为准';

ALTER TABLE erp_product_sku
    RENAME COLUMN specification_summary_json TO specification_summary;

-- 已有来源绑定保存了规格组合名称，用它消除历史 JSON 字符串可能包含的引号。
UPDATE erp_product_sku sku
SET specification_summary = (
    SELECT binding.source_name
    FROM erp_master_source_binding binding
    WHERE binding.tenant_id = sku.tenant_id
      AND binding.source_system = 'DINGHUOBAO'
      AND binding.source_object_type = 'PRODUCT_SKU'
      AND binding.target_id = sku.id
)
WHERE EXISTS (
    SELECT 1
    FROM erp_master_source_binding binding
    WHERE binding.tenant_id = sku.tenant_id
      AND binding.source_system = 'DINGHUOBAO'
      AND binding.source_object_type = 'PRODUCT_SKU'
      AND binding.target_id = sku.id
      AND binding.source_name IS NOT NULL
);
