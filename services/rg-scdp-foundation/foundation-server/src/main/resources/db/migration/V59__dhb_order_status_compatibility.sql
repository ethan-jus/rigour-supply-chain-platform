-- Integration 的归一化来源状态保留为兼容入口，不增加重复的标准选项。
INSERT INTO data_dictionary_item
    (tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,remark,ordinal,
     canonical_dictionary_code,canonical_item_code,created_by)
SELECT 'GLOBAL','DHB_ORDER_STATUS',aliases.alias_code,standard.dictionary_item_name,
       '订货宝归一化来源状态兼容入口',standard.ordinal,'DHB_ORDER_STATUS',standard.dictionary_item_code,'SYSTEM'
FROM (SELECT 'PENDING_PRICING' alias_code,'PRICING' canonical_code
      UNION ALL SELECT 'PENDING_REVIEW','PENDING'
      UNION ALL SELECT 'FORCE_COMPLETED','FORCEDONE') aliases
JOIN data_dictionary_item standard ON standard.tenant_key='GLOBAL'
    AND standard.dictionary_code='DHB_ORDER_STATUS' AND standard.dictionary_item_code=aliases.canonical_code
    AND standard.deleted=0 AND standard.canonical_item_code IS NULL
WHERE NOT EXISTS (SELECT 1 FROM data_dictionary_item existing WHERE existing.tenant_key='GLOBAL'
    AND existing.dictionary_code='DHB_ORDER_STATUS' AND existing.dictionary_item_code=aliases.alias_code);

INSERT INTO data_dictionary_item
    (tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,remark,ordinal,created_by)
SELECT 'GLOBAL','DHB_ORDER_STATUS','DELETED','已删除','来源明确删除，仅供来源状态解析',100,'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL'
    AND dictionary_code='DHB_ORDER_STATUS' AND dictionary_item_code='DELETED');
