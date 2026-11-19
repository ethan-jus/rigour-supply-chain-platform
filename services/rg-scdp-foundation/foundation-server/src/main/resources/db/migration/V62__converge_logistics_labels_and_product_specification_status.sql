-- 两条 V61 历史均保持原文和校验；以下条件更新只补齐尚未执行的分支。
-- 统一运输成本字典名称，已有业务编码继续用于来源对应和运输单据。
UPDATE data_dictionary
SET dictionary_name = CASE dictionary_code
        WHEN 'COST_DELIVERY_MODE' THEN '配送方式'
        WHEN 'COST_TRANSPORT_KIND' THEN '运输渠道'
        WHEN 'COST_LOGISTICS_LEG' THEN '物流环节'
    END,
    revision = revision + 1, updated_by = 'SYSTEM', updated_time = UTC_TIMESTAMP()
WHERE tenant_key = 'GLOBAL' AND deleted = 0
  AND ((dictionary_code = 'COST_DELIVERY_MODE' AND dictionary_name = '承运方式')
    OR (dictionary_code = 'COST_TRANSPORT_KIND' AND dictionary_name = '运输方式')
    OR (dictionary_code = 'COST_LOGISTICS_LEG' AND dictionary_name = '物流类型'));

UPDATE data_dictionary_item
SET dictionary_item_name = CASE dictionary_item_code
        WHEN 'SELF' THEN '自配送'
        WHEN 'THIRD_PARTY' THEN '第三方配送'
    END,
    revision = revision + 1, updated_by = 'SYSTEM', updated_time = UTC_TIMESTAMP()
WHERE tenant_key = 'GLOBAL' AND dictionary_code = 'COST_DELIVERY_MODE' AND deleted = 0
  AND ((dictionary_item_code = 'SELF' AND dictionary_item_name = '自营')
    OR (dictionary_item_code = 'THIRD_PARTY' AND dictionary_item_name = '第三方'));

-- 关闭旧的混合分类入口。历史费用投影按配送方式、运输渠道分别归类。
UPDATE data_dictionary_item
SET deleted = 1, revision = revision + 1, updated_by = 'SYSTEM', updated_time = UTC_TIMESTAMP()
WHERE dictionary_code = 'COST_TRANSPORT_METHOD' AND deleted = 0;
UPDATE data_dictionary
SET deleted = 1, revision = revision + 1, updated_by = 'SYSTEM', updated_time = UTC_TIMESTAMP()
WHERE dictionary_code = 'COST_TRANSPORT_METHOD' AND deleted = 0;

-- 商品规格和规格值使用独立字典，已有租户覆盖继续优先。
INSERT INTO data_dictionary
    (tenant_key, dictionary_code, dictionary_name, dictionary_type, allow_new_items, created_by)
SELECT 'GLOBAL', seed.code, seed.name, 'ERP', FALSE, 'SYSTEM'
FROM (
    SELECT 'PRODUCT_SPECIFICATION_STATUS' AS code, '商品规格状态' AS name
    UNION ALL SELECT 'PRODUCT_SPECIFICATION_VALUE_STATUS', '商品规格值状态'
) seed
WHERE NOT EXISTS (
    SELECT 1 FROM data_dictionary d WHERE d.tenant_key='GLOBAL' AND d.dictionary_code=seed.code
);

INSERT INTO data_dictionary_item
    (tenant_key, dictionary_code, dictionary_item_code, dictionary_item_name, ordinal, created_by)
SELECT 'GLOBAL', d.dictionary_code, seed.code, seed.name, seed.ordinal, 'SYSTEM'
FROM data_dictionary d
CROSS JOIN (
    SELECT 'ACTIVE' AS code, '启用' AS name, 10 AS ordinal
    UNION ALL SELECT 'INACTIVE', '停用', 20
    UNION ALL SELECT 'ARCHIVED', '已归档', 30
) seed
WHERE d.tenant_key='GLOBAL' AND d.deleted=0
  AND d.dictionary_code IN ('PRODUCT_SPECIFICATION_STATUS', 'PRODUCT_SPECIFICATION_VALUE_STATUS')
  AND NOT EXISTS (
      SELECT 1 FROM data_dictionary_item i WHERE i.tenant_key=d.tenant_key
        AND i.dictionary_code=d.dictionary_code
        AND (i.dictionary_item_code=seed.code OR (i.dictionary_item_name=seed.name AND i.canonical_item_code IS NULL))
  );
