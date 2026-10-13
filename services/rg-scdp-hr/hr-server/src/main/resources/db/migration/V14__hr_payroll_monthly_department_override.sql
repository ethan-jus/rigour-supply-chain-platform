-- 工资核定仅影响指定员工、月份的核算，不改写员工档案或历史任职。
ALTER TABLE hr_payroll_input
    ADD COLUMN department_override_id BIGINT NULL COMMENT 'HR核定的当月部门';
