-- CRM V2：保存订货宝业务员来源 ID，允许员工目录延迟同步时先保留归属事实。
ALTER TABLE crm_sales_assignment
    ADD COLUMN source_staff_id VARCHAR(128) NULL
        COMMENT '订货宝来源业务员ID；可在外部员工目录解析前先落库'
        AFTER external_staff_id;

CREATE INDEX idx_crm_sales_assignment_source_staff
    ON crm_sales_assignment (tenant_id, source_staff_id, status);
