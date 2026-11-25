-- 只记录与订单员工及成交时间一致的 HR 主部门任职。地区保持原口径。
ALTER TABLE order_sales_order
    ADD COLUMN register_assignment_id BIGINT NULL,
    ADD COLUMN register_department_id BIGINT NULL,
    ADD COLUMN register_department_name VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
    ADD COLUMN register_employee_code VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
    ADD COLUMN register_order_date DATETIME(6) NULL;
