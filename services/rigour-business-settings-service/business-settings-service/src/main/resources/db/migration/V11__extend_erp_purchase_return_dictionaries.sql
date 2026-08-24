-- 补齐 ERP 新方案运行所需字典项。
-- 业务表只存我方字典项编码；订货宝来源状态仍保留在来源字典和同步绑定中。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('PURCHASE_RETURN_STATUS', '采购退货状态', 'ERP', '采购退货单创建、出库、退款、完成与取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'PIECE', '件', '通用件装/单件商品单位', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'CANCELLED', '已取消', '采购单已取消', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'DRAFT', '草稿', '采购退货单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'WAIT_STOCK_OUT', '待出库', '采购退货单等待退货出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'REFUND_PENDING', '待退款', '采购退货单等待供应商退款或冲账', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'COMPLETED', '已完成', '采购退货单已完成', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'CANCELLED', '已取消', '采购退货单已取消', 50, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;
