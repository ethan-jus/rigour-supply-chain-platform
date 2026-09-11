-- 销售订单保存外部来源订单状态，供订货宝状态展示、筛选和对账使用。
-- 该字段不参与我方销售订单人工流程状态机。
ALTER TABLE order_sales_order
    ADD COLUMN source_status_code VARCHAR(64) NULL COMMENT '来源平台订单状态原值，如订货宝DHB_ORDER_STATUS'
        AFTER source_order_no,
    ADD KEY idx_order_sales_source_status (tenant_id, source_system_code, source_status_code);
