-- 销售订单保留来源平台单号，供业务人员按订货宝订单号搜索和对账。
-- Integration 外部对象映射仍是幂等与跨系统定位的权威来源。

ALTER TABLE order_sales_order
    ADD COLUMN source_system_code VARCHAR(32) NULL COMMENT '来源系统编码；订货宝同步为DINGHUOBAO，手工订单为空' AFTER order_no,
    ADD COLUMN source_order_no VARCHAR(80) NULL COMMENT '来源平台订单号；用于业务对账展示和搜索，不作为集成幂等唯一依据' AFTER source_system_code,
    ADD KEY idx_order_sales_source_order (tenant_id, source_system_code, source_order_no);
