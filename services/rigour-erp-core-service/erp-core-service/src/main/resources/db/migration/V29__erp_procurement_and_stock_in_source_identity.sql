-- ERP 采购订单和入库单补充外部来源识别字段。
-- Portal 用这些字段区分订货宝同步投影和 ERP 内部可操作单据，并支持来源追溯。

ALTER TABLE erp_procurement_order
    ADD COLUMN connector_id CHAR(36) NULL
        COMMENT 'Integration连接器ID；外部同步单据非空，人工或旧历史单据可为空' AFTER procurement_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL
        COMMENT '来源系统编码；订货宝同步时为DINGHUOBAO' AFTER connector_id,
    ADD COLUMN source_document_no VARCHAR(128) NULL
        COMMENT '来源采购单号；订货宝为采购订单号' AFTER source_system_code,
    ADD UNIQUE KEY uk_erp_procurement_source (
        tenant_id, connector_id, source_system_code, source_document_no
    );

ALTER TABLE erp_stock_in_order
    ADD COLUMN connector_id CHAR(36) NULL
        COMMENT 'Integration连接器ID；外部同步单据非空，人工或旧历史单据可为空' AFTER stock_in_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL
        COMMENT '来源系统编码；订货宝同步时为DINGHUOBAO' AFTER connector_id,
    ADD COLUMN source_document_no VARCHAR(128) NULL
        COMMENT '来源入库凭证号；订货宝为入库单号' AFTER source_system_code,
    ADD UNIQUE KEY uk_erp_stock_in_source (
        tenant_id, connector_id, source_system_code, source_document_no
    );

UPDATE erp_procurement_order procurement
    JOIN erp_master_source_binding binding
      ON binding.tenant_id = procurement.tenant_id
     AND binding.target_type = 'PROCUREMENT_ORDER'
     AND binding.target_id = CAST(procurement.id AS CHAR)
     AND binding.source_system = 'DINGHUOBAO'
     AND binding.source_object_type = 'PURCHASE_ORDER'
     AND binding.source_presence = 'PRESENT'
     AND COALESCE(binding.deleted, 0) = 0
   SET procurement.connector_id = binding.connector_id,
       procurement.source_system_code = binding.source_system,
       procurement.source_document_no = COALESCE(binding.source_code, binding.source_object_id)
 WHERE procurement.source_system_code IS NULL
   AND binding.connector_id IS NOT NULL
   AND COALESCE(binding.source_code, binding.source_object_id) IS NOT NULL;

UPDATE erp_stock_in_order stock_in
    JOIN erp_master_source_binding binding
      ON binding.tenant_id = stock_in.tenant_id
     AND binding.target_type = 'STOCK_IN_ORDER'
     AND binding.target_id = CAST(stock_in.id AS CHAR)
     AND binding.source_system = 'DINGHUOBAO'
     AND binding.source_object_type = 'WAREHOUSING_RECEIPT'
     AND binding.source_presence = 'PRESENT'
     AND COALESCE(binding.deleted, 0) = 0
   SET stock_in.connector_id = binding.connector_id,
       stock_in.source_system_code = binding.source_system,
       stock_in.source_document_no = COALESCE(binding.source_code, binding.source_object_id)
 WHERE stock_in.source_system_code IS NULL
   AND binding.connector_id IS NOT NULL
   AND COALESCE(binding.source_code, binding.source_object_id) IS NOT NULL;
