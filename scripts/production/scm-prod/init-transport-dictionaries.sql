-- 运输成本字典初始化。用独立的生产维护连接整份执行，遇错即停。
-- 参数 @scdp_transport_tenant_code 和 @scdp_transport_apply，apply 为 1 写入，0 预览。
-- 需要读取 IAM，维护 Foundation 字典及审计、Integration 来源映射。

SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

SELECT COUNT(*) = 5 INTO @scdp_transport_schema_ready
FROM information_schema.tables
WHERE table_type = 'BASE TABLE' AND engine = 'InnoDB' AND (
    (table_schema = 'rigour_scdp_iam' AND table_name = 'iam_tenant')
    OR (table_schema = 'rigour_scdp_foundation'
        AND table_name IN ('data_dictionary', 'data_dictionary_item', 'settings_operation_audit'))
    OR (table_schema = 'rigour_scdp_integration' AND table_name = 'integration_dictionary_mapping')
);
SELECT IF(@scdp_transport_schema_ready, 'TABLE_CHECK_OK', 'TABLE_CHECK_BLOCKED') AS table_check,
       'rigour_scdp_iam' AS iam, 'rigour_scdp_foundation' AS foundation,
       'rigour_scdp_integration' AS integration;

SET @scdp_transport_id_hex = (
    SELECT HEX(id) FROM rigour_scdp_iam.iam_tenant
    WHERE BINARY tenant_code = BINARY @scdp_transport_tenant_code
      AND status = 'ACTIVE' AND deleted_at IS NULL AND OCTET_LENGTH(id) = 16
);
SET @scdp_transport_tenant_uuid = IF(@scdp_transport_id_hex IS NULL, NULL, LOWER(CONCAT_WS('-',
    SUBSTRING(@scdp_transport_id_hex, 1, 8), SUBSTRING(@scdp_transport_id_hex, 9, 4),
    SUBSTRING(@scdp_transport_id_hex, 13, 4), SUBSTRING(@scdp_transport_id_hex, 17, 4),
    SUBSTRING(@scdp_transport_id_hex, 21, 12))));
SET @scdp_transport_lock_name = CONCAT('scdp.transport-init.', @scdp_transport_tenant_uuid);
SET @scdp_transport_lock_acquired = CASE
    WHEN @scdp_transport_lock_name IS NULL OR NOT @scdp_transport_schema_ready THEN 0
    ELSE COALESCE(GET_LOCK(@scdp_transport_lock_name, 10), 0)
END;

-- 缺项清单存于当前连接变量。检查条数和 JSON 格式，避免截断后误写。
SET @scdp_transport_original_concat_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 16384;

START TRANSACTION;

SELECT dictionary_code AS locked_dictionary
FROM rigour_scdp_foundation.data_dictionary
WHERE BINARY tenant_key = BINARY @scdp_transport_tenant_uuid
  AND dictionary_code IN ('COST_CARRIER', 'COST_DELIVERY_MODE', 'COST_TRANSPORT_KIND',
      'COST_TRANSPORT_PURPOSE', 'COST_LOGISTICS_FEE_TYPE', 'COST_PAYER',
      'COST_PAYMENT_STATUS', 'COST_STATUS')
  AND @scdp_transport_lock_acquired = 1
ORDER BY dictionary_code FOR UPDATE;

SELECT COUNT(*), COUNT(DISTINCT seed.dictionary_code),
       CONCAT('[', COALESCE(GROUP_CONCAT(
           JSON_ARRAY(seed.dictionary_code, seed.item_code, seed.item_name, seed.ordinal)
           ORDER BY seed.dictionary_code, seed.item_code SEPARATOR ','), ''), ']'),
       GROUP_CONCAT(CONCAT(seed.dictionary_code, '/', seed.item_code)),
       GROUP_CONCAT(DISTINCT seed.dictionary_code),
       GROUP_CONCAT(CASE WHEN EXISTS (
           SELECT 1 FROM rigour_scdp_foundation.data_dictionary_item tombstone
           WHERE BINARY tombstone.tenant_key = BINARY @scdp_transport_tenant_uuid
             AND BINARY tombstone.dictionary_code = BINARY seed.dictionary_code
             AND BINARY tombstone.dictionary_item_code = BINARY seed.item_code AND tombstone.deleted <> 0
       ) THEN CONCAT(seed.dictionary_code, '/', seed.item_code, ' 同编码租户字典项已删除')
       WHEN EXISTS (
           SELECT 1 FROM rigour_scdp_foundation.data_dictionary_item existing
           WHERE (existing.tenant_key = 'GLOBAL' OR BINARY existing.tenant_key = BINARY @scdp_transport_tenant_uuid)
             AND BINARY existing.dictionary_code = BINARY seed.dictionary_code
             AND existing.parent_dictionary_item_code IS NULL AND existing.canonical_item_code IS NULL
             AND existing.deleted = 0
             AND CONVERT(TRIM(existing.dictionary_item_name) USING utf8mb4) COLLATE utf8mb4_general_ci
                 = CONVERT(seed.item_name USING utf8mb4) COLLATE utf8mb4_general_ci
             AND BINARY existing.dictionary_item_code <> BINARY seed.item_code
             AND (existing.tenant_key <> 'GLOBAL' OR NOT EXISTS (
                 SELECT 1 FROM rigour_scdp_foundation.data_dictionary_item tenant_override
                 WHERE BINARY tenant_override.tenant_key = BINARY @scdp_transport_tenant_uuid
                   AND tenant_override.dictionary_code = existing.dictionary_code
                   AND tenant_override.dictionary_item_code = existing.dictionary_item_code
                   AND tenant_override.deleted = 0
             ))
       ) THEN CONCAT(seed.dictionary_code, '/', seed.item_code, ' 同名标准项已存在') END SEPARATOR '；')
INTO @scdp_transport_expected_items, @scdp_transport_expected_touches,
     @scdp_transport_missing_items, @scdp_transport_missing_keys, @scdp_transport_touched_codes,
     @scdp_transport_item_conflicts
FROM (
    SELECT 'COST_CARRIER' AS dictionary_code, 'AN' AS item_code, '安能物流' AS item_name, 0 AS ordinal
    UNION ALL SELECT 'COST_CARRIER', 'BS', '百世快递', 0
    UNION ALL SELECT 'COST_CARRIER', 'CN', '菜鸟速递', 0
    UNION ALL SELECT 'COST_CARRIER', 'DB', '德邦快递', 0
    UNION ALL SELECT 'COST_CARRIER', 'EMS', '邮政 EMS', 0
    UNION ALL SELECT 'COST_CARRIER', 'JD', '京东快递', 0
    UNION ALL SELECT 'COST_CARRIER', 'JT', '极兔速递', 0
    UNION ALL SELECT 'COST_CARRIER', 'KY', '跨越速运', 0
    UNION ALL SELECT 'COST_CARRIER', 'LJS', '立即送', 0
    UNION ALL SELECT 'COST_CARRIER', 'ST', '申通快递', 0
    UNION ALL SELECT 'COST_CARRIER', 'YD', '韵达快递', 0
    UNION ALL SELECT 'COST_CARRIER', 'YT', '圆通速递', 0
    UNION ALL SELECT 'COST_CARRIER', 'SF', '顺丰', 10
    UNION ALL SELECT 'COST_CARRIER', 'HLL', '货拉拉', 20
    UNION ALL SELECT 'COST_CARRIER', 'SS', '闪送', 30
    UNION ALL SELECT 'COST_CARRIER', 'ZT', '中通快递', 40
    UNION ALL SELECT 'COST_CARRIER', 'RH', '融辉物流', 50
    UNION ALL SELECT 'COST_CARRIER', 'YMDD', '壹米滴答', 60
    UNION ALL SELECT 'COST_CARRIER', 'CIVIL_AVIATION', '民航承运渠道', 70
    UNION ALL SELECT 'COST_DELIVERY_MODE', 'SELF', '自配送', 10
    UNION ALL SELECT 'COST_DELIVERY_MODE', 'THIRD_PARTY', '第三方配送', 20
    UNION ALL SELECT 'COST_TRANSPORT_KIND', 'ROAD', '公路', 10
    UNION ALL SELECT 'COST_TRANSPORT_KIND', 'AIR', '航空', 20
    UNION ALL SELECT 'COST_TRANSPORT_KIND', 'EXPRESS', '快递', 30
    UNION ALL SELECT 'COST_TRANSPORT_KIND', 'RAIL', '铁路', 40
    UNION ALL SELECT 'COST_TRANSPORT_KIND', 'WATER', '水运', 50
    UNION ALL SELECT 'COST_TRANSPORT_KIND', 'OTHER', '其他', 60
    UNION ALL SELECT 'COST_TRANSPORT_PURPOSE', 'TRANSFER', '仓间调拨', 20
    UNION ALL SELECT 'COST_TRANSPORT_PURPOSE', 'DELIVERY', '销售订单配送', 30
    UNION ALL SELECT 'COST_TRANSPORT_PURPOSE', 'OTHER', '其他费用', 50
    UNION ALL SELECT 'COST_LOGISTICS_FEE_TYPE', 'FREIGHT', '基础运费', 10
    UNION ALL SELECT 'COST_LOGISTICS_FEE_TYPE', 'LOADING', '装卸费', 20
    UNION ALL SELECT 'COST_LOGISTICS_FEE_TYPE', 'FUEL', '燃油费', 30
    UNION ALL SELECT 'COST_LOGISTICS_FEE_TYPE', 'INSURANCE', '运输保险费', 40
    UNION ALL SELECT 'COST_LOGISTICS_FEE_TYPE', 'OTHER', '其他运输费用', 50
    UNION ALL SELECT 'COST_PAYER', 'COMPANY', '本公司', 10
    UNION ALL SELECT 'COST_PAYER', 'SUPPLIER', '供应商', 20
    UNION ALL SELECT 'COST_PAYER', 'CUSTOMER', '客户', 30
    UNION ALL SELECT 'COST_PAYMENT_STATUS', 'UNPAID', '未付款', 10
    UNION ALL SELECT 'COST_PAYMENT_STATUS', 'PAID', '已付款', 20
    UNION ALL SELECT 'COST_PAYMENT_STATUS', 'UNKNOWN', '待核实', 90
    UNION ALL SELECT 'COST_STATUS', 'DRAFT', '待确认', 10
    UNION ALL SELECT 'COST_STATUS', 'CONFIRMED', '已确认', 20
    UNION ALL SELECT 'COST_STATUS', 'VOID', '已作废', 30
) seed
WHERE @scdp_transport_tenant_uuid IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM rigour_scdp_foundation.data_dictionary_item existing
      WHERE (existing.tenant_key = 'GLOBAL' OR BINARY existing.tenant_key = BINARY @scdp_transport_tenant_uuid)
        AND BINARY existing.dictionary_code = BINARY seed.dictionary_code
        AND BINARY existing.dictionary_item_code = BINARY seed.item_code AND existing.deleted = 0
  );

SELECT COUNT(*), GROUP_CONCAT(IF(d.deleted <> 0, d.dictionary_code, NULL))
INTO @scdp_transport_existing_dicts, @scdp_transport_deleted_dicts
FROM rigour_scdp_foundation.data_dictionary d
WHERE BINARY d.tenant_key = BINARY @scdp_transport_tenant_uuid
  AND FIND_IN_SET(BINARY d.dictionary_code, BINARY @scdp_transport_touched_codes) > 0;
SET @scdp_transport_expected_dicts = @scdp_transport_expected_touches - @scdp_transport_existing_dicts;

-- 物流映射只纳入启用的标准承运商，已有人工映射沿用原配置。
SELECT GROUP_CONCAT(IF(m.id IS NULL, seed.target_item_code, NULL)),
       GROUP_CONCAT(IF(m.mapping_status = 'PENDING' AND m.manual_override = 0
           AND m.target_item_code IS NULL AND m.target_dictionary_code IS NULL
           AND BINARY m.source_value = BINARY seed.source_value, seed.target_item_code, NULL))
INTO @scdp_transport_map_insert, @scdp_transport_map_resolve
FROM (
    SELECT '顺丰速运/顺丰冷链' AS source_value, 'SF' AS target_item_code
    UNION ALL SELECT '极兔速递', 'JT'
    UNION ALL SELECT '百世快递', 'BS'
    UNION ALL SELECT '圆通速递', 'YT'
) seed
LEFT JOIN rigour_scdp_foundation.data_dictionary_item tenant_item
    ON BINARY tenant_item.tenant_key = BINARY @scdp_transport_tenant_uuid
    AND tenant_item.dictionary_code = 'COST_CARRIER' AND tenant_item.deleted = 0
    AND BINARY tenant_item.dictionary_item_code = BINARY seed.target_item_code
LEFT JOIN rigour_scdp_foundation.data_dictionary_item global_item
    ON global_item.tenant_key = 'GLOBAL' AND global_item.dictionary_code = 'COST_CARRIER'
    AND global_item.deleted = 0 AND BINARY global_item.dictionary_item_code = BINARY seed.target_item_code
LEFT JOIN rigour_scdp_integration.integration_dictionary_mapping m
    ON BINARY m.tenant_id = BINARY @scdp_transport_tenant_uuid
    AND m.source_system = 'DHB' AND m.source_scope = 'DHB_SHIPMENT_LOGISTICS'
    AND m.dictionary_code = 'COST_CARRIER' AND m.source_field = 'shipment.logistics'
    AND m.source_value_hash = UNHEX(SHA2(seed.source_value, 256))
WHERE @scdp_transport_tenant_uuid IS NOT NULL AND (
    FIND_IN_SET(CONCAT('COST_CARRIER/', seed.target_item_code), @scdp_transport_missing_keys) > 0
    OR (COALESCE(tenant_item.enabled, global_item.enabled) = 1
        AND IF(tenant_item.id IS NULL, global_item.canonical_item_code, tenant_item.canonical_item_code) IS NULL
        AND IF(tenant_item.id IS NULL, global_item.parent_dictionary_item_code, tenant_item.parent_dictionary_item_code) IS NULL)
);

SET @scdp_transport_expected_new_mappings = IF(@scdp_transport_map_insert IS NULL, 0,
    1 + LENGTH(@scdp_transport_map_insert) - LENGTH(REPLACE(@scdp_transport_map_insert, ',', '')));
SET @scdp_transport_expected_pending = IF(@scdp_transport_map_resolve IS NULL, 0,
    1 + LENGTH(@scdp_transport_map_resolve) - LENGTH(REPLACE(@scdp_transport_map_resolve, ',', '')));
SET @scdp_transport_issue = CASE
    WHEN NOT @scdp_transport_schema_ready THEN '三库必要物理表检查失败'
    WHEN @scdp_transport_tenant_uuid IS NULL THEN '未找到有效的生产租户'
    WHEN @scdp_transport_lock_acquired <> 1 THEN '已有初始化执行或无法取得租户锁'
    WHEN @scdp_transport_item_conflicts IS NOT NULL THEN @scdp_transport_item_conflicts
    WHEN @scdp_transport_deleted_dicts IS NOT NULL
        THEN CONCAT('需要核对已删除的租户字典 ', @scdp_transport_deleted_dicts)
    WHEN NOT JSON_VALID(@scdp_transport_missing_items) THEN '缺项清单格式错误'
    WHEN JSON_LENGTH(@scdp_transport_missing_items) <> @scdp_transport_expected_items THEN '缺项清单条数不符'
    ELSE NULL
END;
SET @scdp_transport_write = @scdp_transport_issue IS NULL AND COALESCE(@scdp_transport_apply, 0) = 1;

-- 使用同租户字典主表，满足拼接外键。已有公共定义时沿用其元数据。
INSERT INTO rigour_scdp_foundation.data_dictionary
    (tenant_key, dictionary_code, dictionary_name, dictionary_type, remark,
     allow_new_items, revision, deleted, created_by, updated_by)
SELECT @scdp_transport_tenant_uuid, seed.dictionary_code, COALESCE(baseline.dictionary_name, seed.dictionary_name),
       COALESCE(baseline.dictionary_type, 'COST'), baseline.remark,
       COALESCE(baseline.allow_new_items, seed.allow_new_items), COALESCE(baseline.revision, 1),
       0, 'SYSTEM', 'SYSTEM'
FROM (
    SELECT 'COST_CARRIER' AS dictionary_code, '物流公司' AS dictionary_name, 1 AS allow_new_items
    UNION ALL SELECT 'COST_DELIVERY_MODE', '配送方式', 0
    UNION ALL SELECT 'COST_TRANSPORT_KIND', '运输渠道', 1
    UNION ALL SELECT 'COST_TRANSPORT_PURPOSE', '运输类型', 0
    UNION ALL SELECT 'COST_LOGISTICS_FEE_TYPE', '物流费用项目', 1
    UNION ALL SELECT 'COST_PAYER', '物流费用承担方', 0
    UNION ALL SELECT 'COST_PAYMENT_STATUS', '费用付款状态', 0
    UNION ALL SELECT 'COST_STATUS', '费用确认状态', 0
) seed
LEFT JOIN rigour_scdp_foundation.data_dictionary baseline
    ON baseline.tenant_key = 'GLOBAL' AND baseline.deleted = 0
    AND BINARY baseline.dictionary_code = BINARY seed.dictionary_code
WHERE @scdp_transport_write AND FIND_IN_SET(seed.dictionary_code, @scdp_transport_touched_codes) > 0
  AND NOT EXISTS (
      SELECT 1 FROM rigour_scdp_foundation.data_dictionary local_dict
      WHERE BINARY local_dict.tenant_key = BINARY @scdp_transport_tenant_uuid
        AND BINARY local_dict.dictionary_code = BINARY seed.dictionary_code
  );
SET @scdp_transport_dicts_written = ROW_COUNT();

-- 最多 44 个标准项，派生序号用于读取缺项清单。
INSERT INTO rigour_scdp_foundation.data_dictionary_item
    (tenant_key, dictionary_code, dictionary_item_level, parent_dictionary_item_code,
     dictionary_item_code, dictionary_item_name, ordinal, revision, enabled, locally_managed,
     deleted, created_by, updated_by)
SELECT @scdp_transport_tenant_uuid,
       JSON_UNQUOTE(JSON_EXTRACT(@scdp_transport_missing_items, CONCAT('$[', seq.n, '][0]'))),
       1, NULL,
       JSON_UNQUOTE(JSON_EXTRACT(@scdp_transport_missing_items, CONCAT('$[', seq.n, '][1]'))),
       JSON_UNQUOTE(JSON_EXTRACT(@scdp_transport_missing_items, CONCAT('$[', seq.n, '][2]'))),
       CAST(JSON_UNQUOTE(JSON_EXTRACT(@scdp_transport_missing_items, CONCAT('$[', seq.n, '][3]'))) AS SIGNED),
       1, 1, 1, 0, 'SYSTEM', 'SYSTEM'
FROM (
    SELECT tens.n * 10 + units.n AS n
    FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) tens
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
           UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) units
) seq
WHERE @scdp_transport_write AND seq.n < @scdp_transport_expected_items;
SET @scdp_transport_items_written = ROW_COUNT();

UPDATE rigour_scdp_foundation.data_dictionary d
SET d.revision = d.revision + 1, d.updated_by = 'SYSTEM'
WHERE @scdp_transport_write AND BINARY d.tenant_key = BINARY @scdp_transport_tenant_uuid
  AND d.deleted = 0 AND FIND_IN_SET(BINARY d.dictionary_code, BINARY @scdp_transport_touched_codes) > 0;
SET @scdp_transport_dicts_touched = ROW_COUNT();

INSERT INTO rigour_scdp_foundation.settings_operation_audit
    (tenant_id, actor_id, module_code, action_code, object_code, before_json, after_json)
SELECT @scdp_transport_tenant_uuid, 'SYSTEM', 'DICTIONARY', 'dictionary-item:create',
       CONCAT(i.dictionary_code, ':', i.dictionary_item_code), NULL,
       JSON_OBJECT('dictionaryCode', i.dictionary_code, 'dictionaryItemCode', i.dictionary_item_code,
                   'dictionaryItemName', i.dictionary_item_name, 'ordinal', i.ordinal, 'enabled', TRUE)
FROM rigour_scdp_foundation.data_dictionary_item i
WHERE @scdp_transport_write AND BINARY i.tenant_key = BINARY @scdp_transport_tenant_uuid
  AND i.deleted = 0 AND FIND_IN_SET(BINARY CONCAT(i.dictionary_code, '/', i.dictionary_item_code),
                                     BINARY @scdp_transport_missing_keys) > 0;
SET @scdp_transport_audits_written = ROW_COUNT();

INSERT INTO rigour_scdp_integration.integration_dictionary_mapping
    (tenant_id, source_system, source_scope, dictionary_code, source_field, source_value,
     source_value_hash, target_dictionary_code, target_item_code, mapping_status,
     manual_override, revision, first_seen, last_seen, created_by, updated_by)
SELECT @scdp_transport_tenant_uuid, 'DHB', 'DHB_SHIPMENT_LOGISTICS', 'COST_CARRIER',
       'shipment.logistics', seed.source_value, UNHEX(SHA2(seed.source_value, 256)),
       'COST_CARRIER', seed.target_item_code, 'MAPPED', 1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
       'SYSTEM', 'SYSTEM'
FROM (
    SELECT '顺丰速运/顺丰冷链' AS source_value, 'SF' AS target_item_code
    UNION ALL SELECT '极兔速递', 'JT'
    UNION ALL SELECT '百世快递', 'BS'
    UNION ALL SELECT '圆通速递', 'YT'
) seed
WHERE @scdp_transport_write AND FIND_IN_SET(seed.target_item_code, @scdp_transport_map_insert) > 0;
SET @scdp_transport_new_mappings = ROW_COUNT();

UPDATE rigour_scdp_integration.integration_dictionary_mapping m
JOIN (
    SELECT '顺丰速运/顺丰冷链' AS source_value, 'SF' AS target_item_code
    UNION ALL SELECT '极兔速递', 'JT'
    UNION ALL SELECT '百世快递', 'BS'
    UNION ALL SELECT '圆通速递', 'YT'
) seed ON m.source_value_hash = UNHEX(SHA2(seed.source_value, 256))
      AND BINARY m.source_value = BINARY seed.source_value
SET m.target_dictionary_code = 'COST_CARRIER', m.target_item_code = seed.target_item_code,
    m.mapping_status = 'MAPPED', m.manual_override = 1, m.revision = m.revision + 1, m.updated_by = 'SYSTEM'
WHERE @scdp_transport_write AND BINARY m.tenant_id = BINARY @scdp_transport_tenant_uuid
  AND m.source_system = 'DHB' AND m.source_scope = 'DHB_SHIPMENT_LOGISTICS'
  AND m.dictionary_code = 'COST_CARRIER' AND m.source_field = 'shipment.logistics'
  AND m.mapping_status = 'PENDING' AND m.manual_override = 0
  AND m.target_item_code IS NULL AND m.target_dictionary_code IS NULL
  AND FIND_IN_SET(seed.target_item_code, @scdp_transport_map_resolve) > 0;
SET @scdp_transport_pending_resolved = ROW_COUNT();

SET @scdp_transport_done = @scdp_transport_write
    AND @scdp_transport_expected_dicts = @scdp_transport_dicts_written
    AND @scdp_transport_expected_items = @scdp_transport_items_written
    AND @scdp_transport_expected_touches = @scdp_transport_dicts_touched
    AND @scdp_transport_expected_items = @scdp_transport_audits_written
    AND @scdp_transport_expected_new_mappings = @scdp_transport_new_mappings
    AND @scdp_transport_expected_pending = @scdp_transport_pending_resolved;
SET @scdp_transport_finish = IF(@scdp_transport_done, 'COMMIT', 'ROLLBACK');
PREPARE scdp_transport_finish FROM @scdp_transport_finish;
EXECUTE scdp_transport_finish;
DEALLOCATE PREPARE scdp_transport_finish;
DO IF(@scdp_transport_lock_acquired = 1, RELEASE_LOCK(@scdp_transport_lock_name), NULL);
SET SESSION group_concat_max_len = CAST(@scdp_transport_original_concat_len AS UNSIGNED);

SELECT CASE WHEN @scdp_transport_done THEN 'APPLIED'
            WHEN @scdp_transport_issue IS NOT NULL THEN 'BLOCKED'
            WHEN COALESCE(@scdp_transport_apply, 0) <> 1 THEN 'PREVIEW'
            ELSE 'ROLLED_BACK' END AS result,
       @scdp_transport_tenant_code AS tenant_code,
       @scdp_transport_tenant_uuid AS tenant_uuid,
       @scdp_transport_expected_dicts AS planned_dictionaries,
       @scdp_transport_expected_items AS planned_items,
       @scdp_transport_expected_new_mappings AS planned_new_mappings,
       @scdp_transport_expected_pending AS planned_pending_resolutions;
SELECT @scdp_transport_issue AS issue, @scdp_transport_missing_keys AS planned_item_codes,
       @scdp_transport_map_insert AS planned_mapping_targets,
       @scdp_transport_map_resolve AS planned_pending_targets;
