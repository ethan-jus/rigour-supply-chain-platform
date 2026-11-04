-- 失效来源保留可查询业务状态，与数据库逻辑删除字段分开。
INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,remark,ordinal,created_by)
VALUES('GLOBAL','SALES_ORDER_STATUS','DELETED','已删除','来源明确删除，保留记录供查询，不参与任何业务统计',50,'SYSTEM')
ON DUPLICATE KEY UPDATE dictionary_item_name=IF(dictionary_item_name='DELETED','已删除',dictionary_item_name),enabled=1,deleted=0,revision=revision+1,updated_by='SYSTEM';
UPDATE data_dictionary_item SET remark='订单已取消，保留记录供查询，不参与任何业务统计',revision=revision+1,updated_by='SYSTEM'
WHERE tenant_key='GLOBAL' AND dictionary_code='SALES_ORDER_STATUS' AND dictionary_item_code='CANCELLED' AND deleted=0;
UPDATE data_dictionary SET remark='草稿、进行中、已完成、已取消、已删除；已取消和已删除可查询但不参与业务统计',revision=revision+1,updated_by='SYSTEM'
WHERE tenant_key='GLOBAL' AND dictionary_code='SALES_ORDER_STATUS' AND deleted=0;
