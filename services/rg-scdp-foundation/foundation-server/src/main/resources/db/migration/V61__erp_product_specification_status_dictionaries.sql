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
