-- 转正状态未知保持 NULL，由 HR 核对；不按工龄或岗位名称猜测。
ALTER TABLE hr_employee
 ADD COLUMN regularized BOOLEAN NULL COMMENT '是否转正，NULL为待完善',
 ADD COLUMN regularization_date DATE NULL COMMENT '实际转正日期',
 ADD COLUMN payroll_category VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'SALES销售 HEADQUARTERS总部',
 ADD COLUMN salary_effective_from DATE NULL COMMENT '档案当前薪资生效日期',
 ADD INDEX ix_hr_employee_payroll_category (tenant_id, payroll_category, deleted),
 ADD INDEX ix_hr_employee_regularized (tenant_id, regularized, deleted),
 ADD CONSTRAINT ck_hr_employee_payroll_category CHECK(payroll_category IS NULL OR payroll_category IN ('SALES','HEADQUARTERS'));
