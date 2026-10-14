-- 公司默认保留在 department_id=0；部门规则只对本部门生效。
-- 部门 expected_days=NULL 表示恢复公司默认，保留版本以阻止旧页面覆盖。
ALTER TABLE hr_attendance_rule ADD COLUMN department_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE hr_attendance_rule MODIFY COLUMN expected_days INT NULL;
ALTER TABLE hr_attendance_rule DROP PRIMARY KEY;
ALTER TABLE hr_attendance_rule ADD PRIMARY KEY (tenant_id, attendance_month, department_id);
ALTER TABLE hr_attendance_rule ADD CONSTRAINT ck_hr_attendance_rule_scope
 CHECK (department_id >= 0 AND (department_id > 0 OR expected_days IS NOT NULL));

ALTER TABLE hr_attendance_audit ADD COLUMN department_id BIGINT NULL;
