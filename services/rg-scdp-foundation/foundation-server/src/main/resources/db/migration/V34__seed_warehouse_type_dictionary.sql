-- ERP 仓库类型基线：仓库建档和编辑只保存字典项编码，默认统计口径仍是城市仓。
-- 归属地区复用客户归属地区（CRM 地区树），出库时按客户地区匹配对应地区的仓库。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted, allow_new_items)
VALUES
    ('WAREHOUSE_TYPE', '仓库类型', 'ERP', '仓库承担的库存与出库角色，可按城市仓、云仓、中心仓、门店仓继续维护', 1, 'SYSTEM', 'SYSTEM', 0, TRUE)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    allow_new_items = TRUE,
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('WAREHOUSE_TYPE', 1, NULL, 'CITY', '城市仓', '按城市覆盖发货，出库时按客户归属地区匹配', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_TYPE', 1, NULL, 'CLOUD', '云仓', '第三方云仓或云仓发货', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_TYPE', 1, NULL, 'CENTER', '中心仓', '区域中心仓，覆盖多个城市', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_TYPE', 1, NULL, 'STORE', '门店仓', '门店前店后仓或客户自提点', 40, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    enabled = TRUE,
    updated_by = 'SYSTEM',
    deleted = 0;
