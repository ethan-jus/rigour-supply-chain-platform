-- 保留旧 UUID 员工引用；新档案保存 HR 的真实 Long ID，不转换或猜测历史身份。
ALTER TABLE sales_profile
    MODIFY employee_id BINARY(16) NULL,
    ADD platform_user_id BINARY(16) NULL,
    ADD hr_employee_id BIGINT NULL,
    ADD hr_employee_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
    ADD CONSTRAINT uk_sales_profile_user UNIQUE (tenant_id, platform_user_id),
    ADD CONSTRAINT uk_sales_profile_hr_employee UNIQUE (tenant_id, hr_employee_id),
    ADD CONSTRAINT uk_sales_profile_hr_code UNIQUE (tenant_id, hr_employee_code),
    ADD CONSTRAINT ck_sales_profile_employee_reference CHECK (
        (employee_id IS NOT NULL AND platform_user_id IS NULL AND hr_employee_id IS NULL AND hr_employee_code IS NULL)
        OR (employee_id IS NULL AND platform_user_id IS NOT NULL AND hr_employee_id IS NOT NULL
            AND hr_employee_id > 0 AND hr_employee_code IS NOT NULL)
    );

ALTER TABLE sales_work_day
    MODIFY employee_id BINARY(16) NULL,
    ADD hr_employee_id BIGINT NULL,
    ADD CONSTRAINT uk_sales_work_day_hr_employee_date UNIQUE (tenant_id, hr_employee_id, business_date),
    ADD CONSTRAINT ck_sales_work_day_employee_reference CHECK (
        (employee_id IS NOT NULL AND hr_employee_id IS NULL)
        OR (employee_id IS NULL AND hr_employee_id IS NOT NULL AND hr_employee_id > 0)
    );
