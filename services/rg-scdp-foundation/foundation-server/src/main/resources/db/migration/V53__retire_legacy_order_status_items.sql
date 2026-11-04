-- 保留历史来源名称，只停用不属于我方订单主状态的遗留项。
UPDATE data_dictionary d
SET d.revision=d.revision+1,d.updated_by='SYSTEM'
WHERE d.dictionary_code='SALES_ORDER_STATUS' AND d.deleted=0
  AND EXISTS (SELECT 1 FROM data_dictionary_item i
      WHERE i.tenant_key=d.tenant_key AND i.dictionary_code=d.dictionary_code
        AND i.dictionary_item_code NOT IN ('DRAFT','SUBMITTED','COMPLETED','CANCELLED')
        AND i.enabled=1 AND i.deleted=0);

UPDATE data_dictionary_item
SET enabled=0,revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='SALES_ORDER_STATUS'
  AND dictionary_item_code NOT IN ('DRAFT','SUBMITTED','COMPLETED','CANCELLED')
  AND enabled=1 AND deleted=0;
