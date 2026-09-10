-- Order 销售单归属人员字段从 staff 语义收口到 HR employee 语义。
-- 人员主档归 HR；Order 只保存员工编码和姓名快照，不再把业务人员字段指向 IAM 员工中心。

ALTER TABLE order_sales_order
    DROP INDEX idx_order_sales_owner_staff;

ALTER TABLE order_sales_order
    CHANGE COLUMN owner_staff_code owner_employee_code VARCHAR(50) NULL
        COMMENT '归属销售人员工编码，来自HR员工主档',
    CHANGE COLUMN owner_staff_name_snapshot owner_employee_name_snapshot VARCHAR(100) NULL
        COMMENT '归属销售人员姓名快照';

CREATE INDEX idx_order_sales_order_owner_employee
    ON order_sales_order (tenant_id, owner_employee_code, order_date);

ALTER TABLE order_sales_order
    MODIFY COLUMN owner_sales_user_id VARCHAR(64) NULL
        COMMENT '归属销售用户ID，旧接口兼容字段；新流程优先使用owner_employee_code';

ALTER TABLE order_sales_shipment
    DROP INDEX idx_order_sales_shipment_owner_staff;

ALTER TABLE order_sales_shipment
    CHANGE COLUMN owner_staff_code owner_employee_code VARCHAR(50) NULL
        COMMENT '归属销售人员工编码，来自HR员工主档';

CREATE INDEX idx_order_sales_shipment_owner_employee
    ON order_sales_shipment (tenant_id, owner_employee_code);
