-- 对齐 HR 任职状态，并恢复已有迁移定义但当前字典缺失的 GRAIN；不改历史订单数量。
INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark, ordinal, revision,
    created_by, updated_by, deleted
) VALUES
    ('EMPLOYEE_STATUS', 1, NULL, 'ACTIVE', '在职', '员工当前在职', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'LEFT', '离职', '员工已离职', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'INACTIVE', '停用', '停用不代表已离职', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'PENDING', '待确认', '待入职或来源状态待确认', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'GRAIN', '颗', '按颗计量，不代表包装换算已经确认', 95, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark), ordinal = VALUES(ordinal), revision = revision + 1,
    updated_by = 'SYSTEM', deleted = 0;

UPDATE data_dictionary SET revision = revision + 1, updated_by = 'SYSTEM'
WHERE dictionary_code IN ('EMPLOYEE_STATUS', 'PRODUCT_UNIT');

-- 旧来源同义项保留为历史值，当前选项收口到标准编码。
UPDATE data_dictionary_item
SET deleted = 1, revision = revision + 1, updated_by = 'SYSTEM',
    remark = CONCAT('历史来源同义项，现使用标准员工状态；', COALESCE(remark, ''))
WHERE dictionary_code = 'EMPLOYEE_STATUS' AND LEFT(dictionary_item_code, 5) = 'AUTO_'
  AND dictionary_item_name IN ('在职', '离职', '停用', '待入职', '待确认') AND deleted = 0;
