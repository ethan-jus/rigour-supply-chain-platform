-- 统一收款状态文案，保留状态编码与其他自定义名称。
UPDATE data_dictionary_item
SET dictionary_item_name = '已结清',
    revision = revision + 1,
    updated_by = 'SYSTEM',
    updated_time = UTC_TIMESTAMP()
WHERE dictionary_code = 'ORDER_COLLECTION_STATUS'
  AND dictionary_item_code = 'PAID'
  AND dictionary_item_name = '已收齐'
  AND deleted = 0;
