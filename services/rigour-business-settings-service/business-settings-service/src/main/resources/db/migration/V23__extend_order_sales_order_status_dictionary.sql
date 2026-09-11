-- 增加 Order 销售订单状态字典，避免内部状态码在业务页面直接展示。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_ORDER_STATUS', '销售订单状态', 'ORDER', '销售订单草稿、提交、取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('SALES_ORDER_STATUS', 1, NULL, 'DRAFT', '草稿', '销售订单保存未提交', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_ORDER_STATUS', 1, NULL, 'SUBMITTED', '已提交', '销售订单已提交，可进入出库流程', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_ORDER_STATUS', 1, NULL, 'CANCELLED', '已取消', '销售订单已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'SALES_ORDER_STATUS';
