-- 飞书完整导入的销售订单直接进入已完成状态，业务页面需要稳定字典展示。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_ORDER_STATUS', '销售订单状态', 'ORDER', '销售订单草稿、提交、完成、取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('SALES_ORDER_STATUS', 1, NULL, 'COMPLETED', '已完成', '飞书等外部来源完整订单已完成', 25, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6)
WHERE dictionary_code = 'SALES_ORDER_STATUS';
