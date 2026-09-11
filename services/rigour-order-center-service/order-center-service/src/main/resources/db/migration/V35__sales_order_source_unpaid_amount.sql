ALTER TABLE order_sales_order
    ADD COLUMN source_unpaid_amount DECIMAL(24,6) NULL COMMENT '来源订单显式待收金额；飞书导入保留来源待付金额对账口径' AFTER unpaid_amount;
