INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NEXT_ACTION','RESTOCK','补货承诺',60,'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NEXT_ACTION' AND dictionary_item_code='RESTOCK');
