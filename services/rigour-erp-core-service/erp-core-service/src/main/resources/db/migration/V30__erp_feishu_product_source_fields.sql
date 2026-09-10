-- ERP 商品承接飞书产品主数据来源字段。
-- 结构化分类/品牌ID仍走ERP主数据；飞书原文快照用于导入闭环、筛选和后续补主数据。

ALTER TABLE erp_product
    ADD COLUMN business_line_name VARCHAR(120) NULL COMMENT '业务线名称，飞书业务线原文' AFTER product_name,
    ADD COLUMN category_name_snapshot VARCHAR(120) NULL COMMENT '商品分类名称快照，飞书品类原文' AFTER category_id,
    ADD COLUMN brand_name_snapshot VARCHAR(120) NULL COMMENT '商品品牌名称快照，飞书品牌原文' AFTER brand_id,
    ADD COLUMN industry_name VARCHAR(120) NULL COMMENT '行业名称，飞书行业原文' AFTER brand_name_snapshot,
    ADD COLUMN source_status_name VARCHAR(80) NULL COMMENT '来源商品状态名称' AFTER shelf_status_code,
    ADD COLUMN source_system_code VARCHAR(32) NULL COMMENT '来源系统编码，如FEISHU' AFTER remark,
    ADD COLUMN source_tenant_key VARCHAR(128) NULL COMMENT '来源租户/表标识' AFTER source_system_code,
    ADD COLUMN source_product_id VARCHAR(128) NULL COMMENT '来源商品ID' AFTER source_tenant_key,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '来源单号/来源商品编码' AFTER source_product_id,
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '来源创建时间' AFTER source_document_no,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '来源更新时间' AFTER source_created_at,
    ADD COLUMN source_payload_hash CHAR(64) NULL COMMENT '来源字段SHA-256摘要' AFTER source_updated_at,
    ADD COLUMN source_payload_json JSON NULL COMMENT '来源原始字段快照' AFTER source_payload_hash,
    ADD KEY idx_erp_product_business_line (tenant_id, business_line_name, deleted),
    ADD KEY idx_erp_product_source_doc (tenant_id, source_system_code, source_document_no),
    ADD UNIQUE KEY uk_erp_product_source (tenant_id, source_system_code, source_tenant_key, source_product_id);
