-- Order V25：补齐销售订单/发货单明细的统一本地审计字段。
-- 来源字段 source_* 不属于本地审计字段，本迁移不处理旧订货宝投影表。

ALTER TABLE order_sales_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE order_sales_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE order_sales_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE order_sales_shipment_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE order_sales_shipment_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE order_sales_shipment_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
