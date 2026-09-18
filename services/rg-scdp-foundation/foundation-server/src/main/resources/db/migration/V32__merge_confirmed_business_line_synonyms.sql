-- 2026-09-15 用户确认两组业务线分别同义。
-- 沿用“鹰眼业务”和“城市零售代理”的现有编码；旧名称、编码和业务原始数据保留。
UPDATE data_dictionary_item legacy
JOIN data_dictionary_item standard
  ON standard.dictionary_code = 'BUSINESS_LINE'
 AND standard.dictionary_item_code = 'AUTO_340670D0AE6643CECA561135428529D55B606E0845379'
 AND standard.deleted = 0
 AND standard.canonical_item_code IS NULL
 AND standard.parent_dictionary_item_code <=> legacy.parent_dictionary_item_code
SET legacy.canonical_dictionary_code = standard.dictionary_code,
    legacy.canonical_item_code = standard.dictionary_item_code,
    legacy.revision = legacy.revision + 1,
    legacy.updated_by = 'SYSTEM',
    legacy.updated_time = UTC_TIMESTAMP(6)
WHERE legacy.dictionary_code = 'BUSINESS_LINE'
  AND legacy.dictionary_item_code = 'AUTO_3F893BBA2120A3CE805ED318DF7BE0F207490FEA4BACE'
  AND legacy.deleted = 0
  AND legacy.canonical_item_code IS NULL;

UPDATE data_dictionary_item legacy
JOIN data_dictionary_item standard
  ON standard.dictionary_code = 'BUSINESS_LINE'
 AND standard.dictionary_item_code = 'AUTO_3758C8870549A93C41CE149363FFE3836FCA022EBB5E5'
 AND standard.deleted = 0
 AND standard.canonical_item_code IS NULL
 AND standard.parent_dictionary_item_code <=> legacy.parent_dictionary_item_code
SET legacy.canonical_dictionary_code = standard.dictionary_code,
    legacy.canonical_item_code = standard.dictionary_item_code,
    legacy.revision = legacy.revision + 1,
    legacy.updated_by = 'SYSTEM',
    legacy.updated_time = UTC_TIMESTAMP(6)
WHERE legacy.dictionary_code = 'BUSINESS_LINE'
  AND legacy.dictionary_item_code = 'AUTO_0768CB7ABDABFB83FE4576AEF07970E4D14AAADB13522'
  AND legacy.deleted = 0
  AND legacy.canonical_item_code IS NULL;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    updated_time = UTC_TIMESTAMP(6)
WHERE dictionary_code = 'BUSINESS_LINE'
  AND deleted = 0
  AND EXISTS (
      SELECT 1 FROM data_dictionary_item item
      WHERE item.dictionary_code = 'BUSINESS_LINE'
        AND item.deleted = 0
        AND item.canonical_dictionary_code = 'BUSINESS_LINE'
        AND ((item.dictionary_item_code = 'AUTO_3F893BBA2120A3CE805ED318DF7BE0F207490FEA4BACE'
              AND item.canonical_item_code = 'AUTO_340670D0AE6643CECA561135428529D55B606E0845379')
          OR (item.dictionary_item_code = 'AUTO_0768CB7ABDABFB83FE4576AEF07970E4D14AAADB13522'
              AND item.canonical_item_code = 'AUTO_3758C8870549A93C41CE149363FFE3836FCA022EBB5E5'))
  );
