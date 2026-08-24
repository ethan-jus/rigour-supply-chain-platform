-- 销售回款记录补齐业务快照与人员编码。
-- 回款单属于 Order 自研业务表，订货宝收款单只能同步映射到本表。

ALTER TABLE order_payment_record
    ADD COLUMN sales_order_no_snapshot VARCHAR(50) NULL COMMENT '销售订单号快照' AFTER order_id,
    ADD COLUMN customer_id BIGINT(20) NULL COMMENT 'CRM客户ID，跨服务引用' AFTER sales_order_no_snapshot,
    ADD COLUMN customer_code_snapshot VARCHAR(50) NULL COMMENT '客户编号快照' AFTER customer_id,
    ADD COLUMN customer_name_snapshot VARCHAR(200) NULL COMMENT '客户名称快照' AFTER customer_code_snapshot,
    ADD COLUMN collector_staff_code VARCHAR(50) NULL COMMENT '回款人员工编码，关联IAM员工中心staff_code' AFTER collector_user_id;

ALTER TABLE order_payment_record
    ADD KEY idx_order_payment_staff_code (tenant_id, collector_staff_code, payment_time),
    ADD KEY idx_order_payment_customer (tenant_id, customer_id, payment_time);
