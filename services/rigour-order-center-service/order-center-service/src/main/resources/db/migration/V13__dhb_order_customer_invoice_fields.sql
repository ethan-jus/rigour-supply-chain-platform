-- 补齐订货宝订单详情中的客户标签和发票类型查询字段。
ALTER TABLE order_order
    ADD COLUMN customer_tag VARCHAR(500) NULL COMMENT '订货宝客户标签' AFTER customer_type,
    ADD COLUMN invoice_type VARCHAR(80) NULL COMMENT '订货宝发票类型' AFTER invoice_title;
