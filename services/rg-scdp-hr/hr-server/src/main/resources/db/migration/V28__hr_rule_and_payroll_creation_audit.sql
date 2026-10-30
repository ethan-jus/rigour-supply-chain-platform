-- 仅新增审计字段。历史未保存的创建人/时间保留 NULL，不使用修改人或修改时间回填。
ALTER TABLE hr_payroll_component_version
 ADD COLUMN created_by_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;

ALTER TABLE hr_payroll
 ADD COLUMN created_by_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;

ALTER TABLE hr_attendance_rule
 ADD COLUMN created_by VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN created_by_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN created_time DATETIME(6) NULL;
