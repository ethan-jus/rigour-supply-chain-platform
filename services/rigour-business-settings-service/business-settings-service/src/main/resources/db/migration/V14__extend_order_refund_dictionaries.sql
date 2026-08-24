-- Business Settings V14：销售退款状态字典。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_REFUND_STATUS', '销售退款状态', 'ORDER', '销售退款待确认、已确认、已取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('SALES_REFUND_STATUS', 1, NULL, 'PENDING', '待确认', '退款记录已生成，等待确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_REFUND_STATUS', 1, NULL, 'CONFIRMED', '已确认', '退款已确认并影响订单实收金额', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_REFUND_STATUS', 1, NULL, 'CANCELLED', '已取消', '退款已取消，不影响订单实收金额', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;
