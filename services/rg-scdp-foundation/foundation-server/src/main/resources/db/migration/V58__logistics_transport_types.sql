-- 仅调整字典定义，不改写历史运输费用或历史迁移。
UPDATE data_dictionary SET dictionary_name='运输类型',remark='仓间调拨、销售订单配送、其他费用；采购运费归采购成本',revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='COST_TRANSPORT_PURPOSE' AND deleted=0;
UPDATE data_dictionary_item SET dictionary_item_name='销售订单配送',revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='COST_TRANSPORT_PURPOSE' AND dictionary_item_code='DELIVERY';
UPDATE data_dictionary_item SET dictionary_item_name='其他费用',remark='活动、领用、赠送、品鉴等未产生销售收益的运输费用',revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='COST_TRANSPORT_PURPOSE' AND dictionary_item_code='OTHER';
UPDATE data_dictionary_item SET deleted=1,revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='COST_TRANSPORT_PURPOSE' AND dictionary_item_code IN ('PURCHASE','NON_SALES') AND deleted=0;
