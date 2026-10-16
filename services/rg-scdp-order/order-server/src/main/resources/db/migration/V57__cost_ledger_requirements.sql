-- 成本需求 V1：按来源和场地类型筛选，保留历史未核对类型。
ALTER TABLE order_logistics_cost
    ADD COLUMN source_system VARCHAR(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'UNKNOWN',
    ADD INDEX idx_cost_origin_city (tenant_id,source_system,city_code,cost_date);
-- 旧版手工补录已有明确系统标识，使这些历史记录仍能按原始来源检索。
UPDATE order_logistics_cost
SET source_system=CASE
    WHEN (IFNULL(JSON_CONTAINS(JSON_EXTRACT(data_json,'$.logistics.externalDocuments[*].sourceSystem'),'"DHB"'),0)
        + IFNULL(JSON_CONTAINS(JSON_EXTRACT(data_json,'$.logistics.externalDocuments[*].sourceSystem'),'"OTHER"'),0)
        + IFNULL(JSON_CONTAINS(JSON_EXTRACT(data_json,'$.logistics.externalDocuments[*].sourceSystem'),'"MANUAL"'),0))>1 THEN 'MIXED'
    WHEN JSON_CONTAINS(JSON_EXTRACT(data_json,'$.logistics.externalDocuments[*].sourceSystem'),'"DHB"')=1 THEN 'DHB'
    WHEN JSON_CONTAINS(JSON_EXTRACT(data_json,'$.logistics.externalDocuments[*].sourceSystem'),'"OTHER"')=1 THEN 'OTHER'
    WHEN JSON_CONTAINS(JSON_EXTRACT(data_json,'$.logistics.externalDocuments[*].sourceSystem'),'"MANUAL"')=1 THEN 'MANUAL'
    ELSE 'UNKNOWN' END
WHERE JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.logistics.entryMode'))='MANUAL'
  AND COALESCE(JSON_LENGTH(sources_json),0)=0;
