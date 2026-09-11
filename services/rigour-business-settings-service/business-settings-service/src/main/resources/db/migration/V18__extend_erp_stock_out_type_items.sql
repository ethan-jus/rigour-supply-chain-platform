-- 扩展 ERP 统一出库单类型，支持订货宝 getShipsList 除销售/调拨外的出库类型先落出库单。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('STOCK_OUT_TYPE', 1, NULL, 'PURCHASE_RETURN', '采购退货出库',
     '订货宝采购退货出库，暂先落ERP统一出库单', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'INVENTORY_LOSS', '盘亏出库',
     '订货宝盘亏出库，暂先落ERP统一出库单', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'OTHER', '其他出库',
     '订货宝其他出库，暂先落ERP统一出库单', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'JOINT_OPERATION', '联营出库',
     '订货宝联营出库，暂先落ERP统一出库单', 60, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;
