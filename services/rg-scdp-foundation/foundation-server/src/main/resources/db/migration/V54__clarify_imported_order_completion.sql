-- 当前订货宝承担实际业务流程，来源完结可作为同步订单完成依据；收款独立。
UPDATE data_dictionary
SET remark='草稿、进行中、已完成、已取消；同步订单按来源业务状态，自建订单按履约，收款独立',
    revision=revision+1, updated_by='SYSTEM'
WHERE tenant_key='GLOBAL' AND dictionary_code='SALES_ORDER_STATUS' AND deleted=0;

UPDATE data_dictionary_item
SET remark='同步订单按来源完成或业务结束确认；自建订单按交付签收确认；不代表收款结清',
    revision=revision+1, updated_by='SYSTEM'
WHERE tenant_key='GLOBAL' AND dictionary_code='SALES_ORDER_STATUS'
  AND dictionary_item_code='COMPLETED' AND deleted=0;
