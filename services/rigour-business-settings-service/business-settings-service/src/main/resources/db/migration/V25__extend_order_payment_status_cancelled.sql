-- 增加 Order 销售订单收款状态“已取消”，用于取消订单关闭应收口径。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('PAYMENT_STATUS', '收款状态', 'ORDER', '销售订单回款状态', 1, 'SYSTEM', 'SYSTEM', 0)
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
    ('PAYMENT_STATUS', 1, NULL, 'CANCELLED', '已取消', '销售订单已取消，不再形成待收款', 40, 1, 'SYSTEM', 'SYSTEM', 0)
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
WHERE dictionary_code = 'PAYMENT_STATUS';
