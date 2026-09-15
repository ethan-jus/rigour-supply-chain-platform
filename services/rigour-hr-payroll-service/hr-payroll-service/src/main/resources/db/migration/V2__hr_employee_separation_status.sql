-- 离职与停用分开。仅修复有明确离职证据的旧 INACTIVE，不按姓名或岗位猜测。
ALTER TABLE hr_employee DROP CHECK ck_hr_employee_status;
ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_status
    CHECK (employment_status IN ('ACTIVE', 'LEFT', 'INACTIVE', 'PENDING'));

UPDATE hr_employee
SET employment_status = 'LEFT', revision = revision + 1, updated_by = 'SYSTEM'
WHERE employment_status = 'INACTIVE'
  AND (leave_date IS NOT NULL
       OR JSON_UNQUOTE(JSON_EXTRACT(source_payload_json, '$."在职状态"')) IN ('离职', 'LEFT')
       OR JSON_UNQUOTE(JSON_EXTRACT(source_payload_json, '$."状态"')) IN ('离职', 'LEFT'));
