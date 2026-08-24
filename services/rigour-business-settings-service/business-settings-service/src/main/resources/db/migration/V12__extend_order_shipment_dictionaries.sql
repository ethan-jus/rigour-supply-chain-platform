-- 补齐 Order 发货单新方案所需字典。
-- 业务表只保存我方字典项编码；订货宝发货状态在同步层映射后再写入业务表。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_SHIPMENT_STATUS', '销售发货单状态', 'ORDER', '销售发货单创建、发货、签收、取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
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
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'CREATED', '已创建', '发货单已生成，等待实际发货', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'SHIPPED', '已发货', '发货单已交付物流或配送人员', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'SIGNED', '已签收', '客户已签收', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'CANCELLED', '已取消', '发货单已取消', 40, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;
