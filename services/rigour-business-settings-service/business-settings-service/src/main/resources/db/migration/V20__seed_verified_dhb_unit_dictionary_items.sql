-- 补齐订货宝来源计量单位原始值映射。
-- base_units/middle_units/container_units 是单位层级字段名，不是计量单位名称，不在此处建项。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('DHB_UNIT', 1, NULL, 'PIECE', '件', '订货宝来源单位原值：件', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BOX', '箱', '订货宝来源单位原值：箱', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BUCKET', '桶', '订货宝来源单位原值：桶', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'PORTION', '份', '订货宝来源单位原值：份', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'SET', '套', '订货宝来源单位原值：套', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BED', '床', '订货宝来源单位原值：床', 60, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'PAIR', '副', '订货宝来源单位原值：副', 70, 1, 'SYSTEM', 'SYSTEM', 0)
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
WHERE dictionary_code = 'DHB_UNIT';
