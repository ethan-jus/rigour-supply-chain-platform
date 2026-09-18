ALTER TABLE hr_employee ADD COLUMN job_grade VARCHAR(32) NULL COMMENT '员工职级',
    ADD COLUMN local_profile_authoritative BOOLEAN NOT NULL DEFAULT FALSE COMMENT '已核定花名册资料由HR维护，来源同步仅保留来源记录',
    ADD INDEX idx_hr_employee_grade (tenant_id, job_grade, deleted);
UPDATE hr_employee e JOIN hr_position p ON p.tenant_id=e.tenant_id AND p.position_code=e.primary_position_code
SET e.job_grade='S1' WHERE e.deleted=0 AND p.deleted=0 AND p.position_name='业务员' AND e.job_grade IS NULL;

ALTER TABLE hr_employee_profile
    ADD COLUMN graduation_school VARCHAR(128) NULL,
    ADD COLUMN major VARCHAR(128) NULL,
    ADD COLUMN regular_salary VARCHAR(64) NULL COMMENT '花名册薪资原文，仅作档案记录',
    ADD COLUMN probation_salary VARCHAR(64) NULL COMMENT '试用期薪资原文，仅作档案记录',
    ADD COLUMN probation_period VARCHAR(32) NULL;
