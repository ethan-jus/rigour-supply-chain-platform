-- 仅收敛系统字典展示名称，保留租户自定义项和稳定编码。
UPDATE data_dictionary_item SET dictionary_item_name='中心仓到城市仓',revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='COST_LOGISTICS_LEG' AND dictionary_item_code='TRANSFER' AND deleted=0;
UPDATE data_dictionary_item SET dictionary_item_name='城市仓到客户',revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='COST_LOGISTICS_LEG' AND dictionary_item_code='DELIVERY' AND deleted=0;
