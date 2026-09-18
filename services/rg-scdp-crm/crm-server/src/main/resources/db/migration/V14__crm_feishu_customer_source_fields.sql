-- CRM 客户/门店承接飞书导入字段。
-- 飞书原始全量仍保留在 Integration raw row；这里补业务查询、筛选和来源幂等所需字段。

ALTER TABLE crm_customer
    MODIFY COLUMN region_code VARCHAR(64) NULL COMMENT '客户归属区域/城市编码，关联CRM客户区域主数据',
    ADD COLUMN region_name VARCHAR(80) NULL COMMENT '客户业务区域名称，飞书区域原文' AFTER region_code,
    ADD COLUMN city_name VARCHAR(80) NULL COMMENT '客户城市名称，飞书城市原文' AFTER region_name,
    ADD COLUMN customer_source_name VARCHAR(120) NULL COMMENT '客户来源名称，飞书商家来源原文' AFTER city_name,
    ADD COLUMN business_category_name VARCHAR(120) NULL COMMENT '客户/门店业务类目或属性原文' AFTER customer_source_name,
    ADD COLUMN source_system_code VARCHAR(32) NULL COMMENT '来源系统编码，如FEISHU' AFTER remark,
    ADD COLUMN source_tenant_key VARCHAR(128) NULL COMMENT '来源租户/表标识' AFTER source_system_code,
    ADD COLUMN source_customer_id VARCHAR(128) NULL COMMENT '来源客户或门店ID' AFTER source_tenant_key,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '来源单号/来源业务编码' AFTER source_customer_id,
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '来源创建时间' AFTER source_document_no,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '来源更新时间' AFTER source_created_at,
    ADD COLUMN source_payload_hash CHAR(64) NULL COMMENT '来源字段SHA-256摘要' AFTER source_updated_at,
    ADD COLUMN source_payload_json JSON NULL COMMENT '来源原始字段快照' AFTER source_payload_hash,
    ADD KEY idx_crm_customer_area_city (tenant_id, region_name, city_name, deleted),
    ADD KEY idx_crm_customer_source_doc (tenant_id, source_system_code, source_document_no),
    ADD UNIQUE KEY uk_crm_customer_source (tenant_id, source_system_code, source_tenant_key, source_customer_id);
