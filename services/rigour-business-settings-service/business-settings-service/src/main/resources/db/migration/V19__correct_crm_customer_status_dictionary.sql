-- CRM 自研客户状态与 merchant-crm 服务枚举对齐：停用统一使用 INACTIVE。
-- V8 曾将 CUSTOMER_STATUS 停用项写成 DISABLED，导致 Portal 筛选发出的状态码无法被 CRM 服务接受。

UPDATE data_dictionary_item
SET dictionary_item_name = '停用',
    remark = '客户暂不可用于新业务',
    ordinal = 20,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'CUSTOMER_STATUS'
  AND dictionary_item_code = 'INACTIVE';

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('CUSTOMER_STATUS', 1, NULL, 'INACTIVE', '停用', '客户暂不可用于新业务', 20, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary_item
SET dictionary_item_level = 1,
    parent_dictionary_item_code = NULL,
    dictionary_item_name = '停用',
    remark = '已迁移为 CUSTOMER_STATUS/INACTIVE',
    ordinal = 20,
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'CUSTOMER_STATUS'
  AND dictionary_item_code = 'DISABLED';
