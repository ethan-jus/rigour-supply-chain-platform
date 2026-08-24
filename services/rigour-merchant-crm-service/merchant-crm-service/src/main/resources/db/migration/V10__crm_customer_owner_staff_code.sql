-- CRM V10：自研客户归属销售人员改为 IAM 员工业务编码。
--
-- 业务口径：
-- 1. owner_staff_code 是 CRM、Order、IAM 关联归属销售人员的主字段。
-- 2. owner_staff_name_snapshot 用于列表和单据展示人员名称，避免跨服务实时查询拖慢页面。
-- 3. owner_sales_user_id/owner_sales_name 仅保留旧接口和旧数据兼容，不再作为新同步关联依据。

ALTER TABLE crm_customer
    ADD COLUMN owner_staff_code VARCHAR(50) NULL COMMENT '归属销售人员员工编码，来自IAM员工中心'
        AFTER owner_sales_name,
    ADD COLUMN owner_staff_name_snapshot VARCHAR(100) NULL COMMENT '归属销售人员姓名快照'
        AFTER owner_staff_code;

CREATE INDEX idx_crm_customer_owner_staff
    ON crm_customer (tenant_id, owner_staff_code);

ALTER TABLE crm_customer
    MODIFY COLUMN owner_sales_user_id VARCHAR(64) NULL
        COMMENT '归属销售用户ID，旧接口兼容字段；新流程优先使用owner_staff_code',
    MODIFY COLUMN owner_sales_name VARCHAR(100) NULL
        COMMENT '归属销售名称快照，旧接口兼容字段；新流程优先使用owner_staff_name_snapshot';
