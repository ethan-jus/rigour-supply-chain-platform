-- 在 Sales V27 迁移后由 MySQL 管理员执行。仅 BI 刷新读取固定业务编码，不读取手机/地址/凭据。
GRANT SELECT (salesperson_id)
ON rigour_sales_work.temp_sales_checkin_submission TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, salesperson_id, employee_code)
ON rigour_sales_work.temp_sales_checkin_employee_link TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, store_id, customer_id, customer_code)
ON rigour_sales_work.temp_sales_checkin_customer_link TO 'rigour_bi_app'@'%';
