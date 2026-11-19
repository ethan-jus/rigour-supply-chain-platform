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
