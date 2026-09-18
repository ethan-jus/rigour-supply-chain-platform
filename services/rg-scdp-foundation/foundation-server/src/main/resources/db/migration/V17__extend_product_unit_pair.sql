-- 补齐订货宝真实来源单位“副”，供商品和订单同步落我方 PRODUCT_UNIT。

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'PAIR', '副', '球杆皮头等按副计量的商品单位', 70, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;
