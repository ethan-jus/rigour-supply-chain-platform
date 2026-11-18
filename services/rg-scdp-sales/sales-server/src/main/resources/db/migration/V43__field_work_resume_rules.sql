-- Keep historical window columns as published evidence; runtime uses one business-day boundary.
ALTER TABLE sales_field_policy_version
    ADD COLUMN allow_resume TINYINT UNSIGNED NOT NULL DEFAULT 1,
    ADD COLUMN work_hours_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1;
ALTER TABLE sales_punch_event
    ADD COLUMN resume_reason VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;
