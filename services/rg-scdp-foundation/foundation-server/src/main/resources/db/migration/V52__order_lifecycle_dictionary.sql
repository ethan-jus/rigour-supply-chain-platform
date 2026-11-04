-- 订单主状态描述我方生命周期，履约进度与收款状态独立维护。
UPDATE data_dictionary
SET dictionary_name='订单状态', remark='草稿、进行中、已完成、已取消；完成以全部交付签收为准，收款独立',
    revision=revision+1, updated_by='SYSTEM'
WHERE tenant_key='GLOBAL' AND dictionary_code='SALES_ORDER_STATUS' AND deleted=0;
UPDATE data_dictionary_item
SET dictionary_item_name='进行中', remark='订单已确认，正在履约', revision=revision+1, updated_by='SYSTEM'
WHERE dictionary_code='SALES_ORDER_STATUS' AND dictionary_item_code='SUBMITTED'
  AND dictionary_item_name='已提交' AND deleted=0;
UPDATE data_dictionary_item
SET remark='全部交付签收完成，不以收款结清或来源系统状态作为完成条件', revision=revision+1, updated_by='SYSTEM'
WHERE tenant_key='GLOBAL' AND dictionary_code='SALES_ORDER_STATUS' AND dictionary_item_code='COMPLETED' AND deleted=0;
