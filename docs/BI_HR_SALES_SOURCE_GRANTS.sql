-- 共享 DEV：用已有 MySQL 管理员连接执行。仅新增 BI 刷新所需的四张表列级 SELECT。
-- 应用账号/迁移账号没有 GRANT OPTION，不能代替管理员执行；不修改业务记录。
GRANT SELECT (tenant_id, employee_code, employee_name, employment_status, city_name, primary_position_code, primary_position_name_snapshot, job_category, department_name_snapshot, entry_date, leave_date, deleted)
ON rigour_hr.hr_employee TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, position_code, position_name, deleted)
ON rigour_hr.hr_position TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, id, store_id, status, deletion_state, submitted_at, review_status)
ON rigour_sales_work.temp_sales_checkin_submission TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, id, city)
ON rigour_sales_work.temp_sales_checkin_store TO 'rigour_bi_app'@'%';

-- 用 BI 应用账号重新连接后验证；LIMIT 0 只检查访问能力，不返回人员/拜访数据。
-- SELECT employee_code, employment_status FROM rigour_hr.hr_employee LIMIT 0;
-- SELECT position_code, position_name FROM rigour_hr.hr_position LIMIT 0;
-- SELECT store_id, submitted_at FROM rigour_sales_work.temp_sales_checkin_submission LIMIT 0;
-- SELECT id, city FROM rigour_sales_work.temp_sales_checkin_store LIMIT 0;
