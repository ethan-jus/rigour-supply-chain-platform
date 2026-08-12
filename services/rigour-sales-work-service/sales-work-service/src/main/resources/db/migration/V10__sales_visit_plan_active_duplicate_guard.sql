-- 同一销售、同一天、同一门店只能存在一个待执行或进行中的计划。
-- 已完成/已取消计划的生成列为 NULL，允许后续重新安排，不删除历史计划。

ALTER TABLE sales_visit_plan
    ADD COLUMN active_store_id BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('PLANNED','IN_PROGRESS') THEN store_id ELSE NULL END
        ) STORED AFTER store_id,
    ADD CONSTRAINT uk_sales_visit_plan_active_store
        UNIQUE (tenant_id, sales_profile_id, planned_date, active_store_id);
