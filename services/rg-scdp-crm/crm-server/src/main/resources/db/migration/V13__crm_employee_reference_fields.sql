-- CRM V13：客户归属人员字段从 IAM 员工语义改为 HR 员工语义。
--
-- 背景：人员主档归 HR 管理，IAM 只负责账号、角色和权限。
-- 本迁移仅重命名 CRM 本地引用字段，不删除订货宝来源人员 ID，避免影响订货宝手动同步追溯。

ALTER TABLE crm_customer
    DROP INDEX idx_crm_customer_owner_staff;

ALTER TABLE crm_customer
    RENAME COLUMN owner_staff_code TO owner_employee_code,
    RENAME COLUMN owner_staff_name_snapshot TO owner_employee_name_snapshot;

CREATE INDEX idx_crm_customer_owner_employee
    ON crm_customer (tenant_id, owner_employee_code);

ALTER TABLE crm_customer
    MODIFY COLUMN owner_sales_user_id VARCHAR(64) NULL
        COMMENT '归属销售用户ID，旧接口兼容字段；新流程优先使用owner_employee_code',
    MODIFY COLUMN owner_sales_name VARCHAR(100) NULL
        COMMENT '归属销售名称快照，旧接口兼容字段；新流程优先使用owner_employee_name_snapshot',
    MODIFY COLUMN owner_employee_code VARCHAR(50) NULL
        COMMENT '归属销售员工编码，来自HR员工主档',
    MODIFY COLUMN owner_employee_name_snapshot VARCHAR(100) NULL
        COMMENT '归属销售员工姓名快照';

ALTER TABLE crm_sales_assignment
    DROP INDEX idx_crm_sales_assignment_iam_staff;

ALTER TABLE crm_sales_assignment
    RENAME COLUMN iam_staff_code TO employee_code,
    RENAME COLUMN iam_staff_name_snapshot TO employee_name_snapshot;

CREATE INDEX idx_crm_sales_assignment_employee
    ON crm_sales_assignment (tenant_id, employee_code, status);

UPDATE crm_sales_assignment
   SET assignee_type = 'EMPLOYEE'
 WHERE assignee_type = 'IAM_STAFF';

ALTER TABLE crm_sales_assignment
    MODIFY COLUMN employee_code VARCHAR(50) NULL
        COMMENT '员工编码，跨服务关联HR员工主档',
    MODIFY COLUMN employee_name_snapshot VARCHAR(128) NULL
        COMMENT '员工姓名快照',
    MODIFY COLUMN assignee_type VARCHAR(32) NOT NULL
        COMMENT '归属对象类型：EMPLOYEE HR员工，SOURCE_STAFF来源人员待解析，SALES_TEAM销售团队；EXTERNAL_STAFF仅旧数据兼容';
