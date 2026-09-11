-- ERP出库单补充外部来源幂等键。
-- 订货宝出库/发货单按 ships_num 唯一投影，避免同一销售订单多次发货时被 sales_order_id 限制，
-- 也避免同步重试重复扣减库存。

ALTER TABLE erp_stock_out_order
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '来源系统编码；订货宝同步时为DINGHUOBAO' AFTER stock_out_no,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '来源出库/发货单号；订货宝为ships_num' AFTER source_system_code,
    ADD UNIQUE KEY uk_erp_stock_out_source (tenant_id, source_system_code, source_document_no),
    ADD KEY idx_erp_stock_out_source (tenant_id, source_system_code, source_document_no);
