-- Order V18：销售订单归属人员改为 IAM 员工业务编码。
--
-- 业务口径：
-- 1. owner_staff_code 是跨 CRM、Order、IAM 关联人员的主字段。
-- 2. owner_sales_user_id 仅保留兼容旧接口入参和旧数据，不再作为新同步的关联依据。
-- 3. owner_staff_name_snapshot 记录订单创建或同步时的员工姓名快照。

ALTER TABLE order_sales_order
    ADD COLUMN owner_staff_code VARCHAR(50) NULL COMMENT '归属销售人员员工编码，来自IAM员工中心'
        AFTER owner_sales_name,
    ADD COLUMN owner_staff_name_snapshot VARCHAR(100) NULL COMMENT '归属销售人员姓名快照'
        AFTER owner_staff_code;

CREATE INDEX idx_order_sales_owner_staff
    ON order_sales_order (tenant_id, owner_staff_code, order_date);

ALTER TABLE order_sales_order
    MODIFY COLUMN owner_sales_user_id VARCHAR(64) NULL
        COMMENT '归属销售用户ID，旧接口兼容字段；新流程优先使用owner_staff_code';
