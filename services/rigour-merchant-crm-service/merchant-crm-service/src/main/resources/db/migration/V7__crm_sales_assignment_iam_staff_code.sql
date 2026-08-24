-- CRM V7：客户归属人员改为引用 IAM 员工业务编码。
--
-- 业务口径：
-- 1. CRM 不再维护员工主档，订货宝员工先同步到 IAM 人员中心。
-- 2. CRM 客户归属只保存 iam_staff_code 与名称快照；source_staff_id 仅用于来源追溯。
-- 3. 旧 external_staff_id 暂留作兼容字段，待所有引用审计完成后再删除旧表和外键。

ALTER TABLE crm_sales_assignment
    ADD COLUMN iam_staff_code VARCHAR(50) NULL COMMENT 'IAM员工编码，跨服务关联使用我方业务编码'
        AFTER source_staff_id,
    ADD COLUMN iam_staff_name_snapshot VARCHAR(128) NULL COMMENT 'IAM员工姓名快照'
        AFTER iam_staff_code;

CREATE INDEX idx_crm_sales_assignment_iam_staff
    ON crm_sales_assignment (tenant_id, iam_staff_code, status);

ALTER TABLE crm_sales_assignment
    MODIFY COLUMN assignee_type VARCHAR(32) NOT NULL
        COMMENT '归属对象类型：IAM_STAFF员工中心人员，SOURCE_STAFF来源人员待解析，SALES_TEAM销售团队；EXTERNAL_STAFF仅旧数据兼容';
