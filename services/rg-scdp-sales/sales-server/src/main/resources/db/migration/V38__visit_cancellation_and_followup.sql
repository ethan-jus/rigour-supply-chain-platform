ALTER TABLE sales_visit
    ADD cancelled_at DATETIME(6) NULL,
    ADD cancellation_reason VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
    DROP COLUMN crm_fieldwork_required;

ALTER TABLE sales_visit_plan
    ADD source_capture_id BINARY(16) NULL,
    ADD crm_store_id BIGINT NULL,
    ADD action_code VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
    ADD target_snapshot_json JSON NULL,
    ADD UNIQUE KEY uk_sales_plan_source_capture (tenant_id,source_capture_id);
