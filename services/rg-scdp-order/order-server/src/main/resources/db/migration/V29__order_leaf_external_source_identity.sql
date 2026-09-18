ALTER TABLE order_sales_shipment
    ADD COLUMN connector_id CHAR(36) NULL COMMENT '外部连接器ID' AFTER shipment_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '领域来源系统编码，订货宝统一为DINGHUOBAO' AFTER connector_id,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '外部来源发货单号' AFTER source_system_code,
    ADD UNIQUE KEY uk_order_sales_shipment_source_identity
        (tenant_id, connector_id, source_system_code, source_document_no);

ALTER TABLE order_payment_record
    ADD COLUMN connector_id CHAR(36) NULL COMMENT '外部连接器ID' AFTER payment_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '领域来源系统编码，订货宝统一为DINGHUOBAO' AFTER connector_id,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '外部来源收款单号' AFTER source_system_code,
    ADD UNIQUE KEY uk_order_payment_record_source_identity
        (tenant_id, connector_id, source_system_code, source_document_no);

ALTER TABLE order_fund_document
    ADD COLUMN connector_id CHAR(36) NULL COMMENT '外部连接器ID' AFTER document_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '领域来源系统编码，订货宝统一为DINGHUOBAO' AFTER connector_id,
    ADD KEY idx_order_fund_document_source_identity
        (tenant_id, connector_id, source_system_code, source_document_no);

UPDATE order_fund_document
SET source_system_code = 'DINGHUOBAO'
WHERE deleted = 0
  AND source_system_code IS NULL
  AND (source_document_no IS NOT NULL OR source_order_no IS NOT NULL OR payment_serial_no IS NOT NULL);

UPDATE order_sales_shipment s
JOIN order_sales_order o
  ON o.tenant_id = s.tenant_id
 AND o.id = s.sales_order_id
 AND o.deleted = 0
SET s.source_system_code = o.source_system_code
WHERE s.deleted = 0
  AND s.source_system_code IS NULL
  AND o.source_system_code = 'DINGHUOBAO'
  AND (s.created_by = 'SYSTEM' OR s.updated_by = 'SYSTEM');

UPDATE order_payment_record p
JOIN order_sales_order o
  ON o.tenant_id = p.tenant_id
 AND o.id = p.order_id
 AND o.deleted = 0
SET p.source_system_code = o.source_system_code
WHERE p.deleted = 0
  AND p.source_system_code IS NULL
  AND o.source_system_code = 'DINGHUOBAO'
  AND (p.created_by = 'SYSTEM' OR p.updated_by = 'SYSTEM');
