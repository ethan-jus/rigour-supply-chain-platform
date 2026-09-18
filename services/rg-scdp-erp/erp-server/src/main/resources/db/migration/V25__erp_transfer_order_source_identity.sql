-- ERP调拨单补充外部来源幂等键。
-- 订货宝调拨出库暂无独立调拨单接口时，按 ships_num 反推我方调拨单并保持重复同步幂等。

ALTER TABLE erp_transfer_order
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '来源系统编码；订货宝同步时为DINGHUOBAO' AFTER transfer_no,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '来源调拨出库单号；订货宝为ships_num' AFTER source_system_code,
    ADD UNIQUE KEY uk_erp_transfer_source (tenant_id, source_system_code, source_document_no),
    ADD KEY idx_erp_transfer_external_source (tenant_id, source_system_code, source_document_no);
