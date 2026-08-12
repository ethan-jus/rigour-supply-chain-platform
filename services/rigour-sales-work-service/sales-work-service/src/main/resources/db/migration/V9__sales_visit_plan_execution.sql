-- 主管计划到销售执行闭环：一个计划最多关联一次真实拜访，并固化可执行状态机。

ALTER TABLE sales_visit
    ADD CONSTRAINT uk_sales_visit_plan_execution UNIQUE (tenant_id, visit_plan_id);

ALTER TABLE sales_visit_plan
    ADD CONSTRAINT ck_sales_visit_plan_status
        CHECK (status IN ('PLANNED','IN_PROGRESS','COMPLETED','CANCELLED')),
    ADD INDEX idx_sales_visit_plan_management (tenant_id, planned_date, status, created_at);
