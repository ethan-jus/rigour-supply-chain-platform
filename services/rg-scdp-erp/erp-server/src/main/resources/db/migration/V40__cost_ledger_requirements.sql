-- 成本需求 V1：按来源和场地类型筛选，保留历史未核对类型。
ALTER TABLE erp_cost_entry
    ADD COLUMN source_system VARCHAR(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN premises_type VARCHAR(20) COLLATE utf8mb4_general_ci NULL,
    ADD INDEX idx_cost_origin_city (tenant_id,source_system,city_code,cost_date);
UPDATE erp_cost_entry SET premises_type = CASE
    WHEN JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.rental.premisesType')) IN ('WAREHOUSE','OFFICE')
        THEN JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.rental.premisesType'))
    WHEN warehouse_id IS NOT NULL THEN 'WAREHOUSE' ELSE 'UNKNOWN' END
    WHERE category_code='RENTAL';
CREATE INDEX idx_cost_premises_month ON erp_cost_entry(tenant_id,category_code,premises_type,cost_month);
-- 仅回填已保存且可明确识别的外部来源；旧系统快照缺少原始来源时仍为 UNKNOWN。
UPDATE erp_cost_entry
SET source_system=JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.externalDocument.sourceSystem'))
WHERE category_code IN ('PURCHASE','LOSS')
  AND COALESCE(JSON_LENGTH(sources_json),0)=0
  AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.externalDocument.sourceSystem')) IN ('DHB','OTHER','MANUAL');
