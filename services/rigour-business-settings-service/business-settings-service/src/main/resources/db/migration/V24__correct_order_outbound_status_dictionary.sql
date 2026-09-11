-- 修正 Order 销售订单出库状态字典编码，与后端 SalesOrderOutboundStatus 枚举保持一致。
-- V8 曾写入 PARTIAL/COMPLETED/SHORTAGE，但当前订单服务只接受 PENDING/PARTIAL_OUT/OUT_CONFIRMED。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('OUTBOUND_STATUS', '出库状态', 'ORDER', '销售订单出库状态', 1, 'SYSTEM', 'SYSTEM', 0)
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
    ('OUTBOUND_STATUS', 1, NULL, 'PENDING', '待出库', '销售订单尚未生成出库结果', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'PARTIAL_OUT', '部分出库', '销售订单已有部分商品出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'OUT_CONFIRMED', '已出库', '销售订单已完成出库确认', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary_item
SET remark = '已迁移为 OUTBOUND_STATUS/PARTIAL_OUT',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'PARTIAL';

UPDATE data_dictionary_item
SET remark = '已迁移为 OUTBOUND_STATUS/OUT_CONFIRMED',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'COMPLETED';

UPDATE data_dictionary_item
SET remark = '当前销售订单出库状态机未使用该状态',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'SHORTAGE';

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'OUTBOUND_STATUS';
