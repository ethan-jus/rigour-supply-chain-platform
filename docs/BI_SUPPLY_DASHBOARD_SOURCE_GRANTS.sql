-- 供应链 BI 刷新任务最小源表只读授权。
-- 使用 MySQL 管理账号在共享 DEV 执行；不要授整库权限，不要授 DDL。

GRANT SELECT ON rigour_crm.crm_customer TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_crm.crm_customer_area TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_crm.crm_customer_type TO 'rigour_bi_app'@'%';

GRANT SELECT ON rigour_order.order_sales_order TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_order.order_sales_order_line TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_order.order_payment_record TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_order.order_refund_record TO 'rigour_bi_app'@'%';

GRANT SELECT ON rigour_integration.integration_raw_landing TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_integration.integration_feishu_import_raw_row TO 'rigour_bi_app'@'%';
-- Integration V22 完成后执行；仅在用户主动生成复核时读取在线证据，历史查询只读 BI 快照。
GRANT SELECT ON rigour_integration.integration_feishu_online_capture TO 'rigour_bi_app'@'%';

GRANT SELECT ON rigour_erp.erp_stock_balance TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_inventory_warehouse TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_product TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_product_variant TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_product_category TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_product_brand TO 'rigour_bi_app'@'%';

-- HR员工和岗位只读权限，仅供定时/手动刷新本地员工投影。
GRANT SELECT (tenant_id, employee_code, employee_name, employment_status, city_name, primary_position_code, primary_position_name_snapshot, job_category, department_name_snapshot, entry_date, leave_date, deleted) ON rigour_hr.hr_employee TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, position_code, position_name, deleted) ON rigour_hr.hr_position TO 'rigour_bi_app'@'%';

-- Sales拜访与门店只读权限，仅供刷新建联本地投影。
GRANT SELECT (tenant_id, id, store_id, salesperson_id, status, deletion_state, submitted_at, review_status) ON rigour_sales_work.temp_sales_checkin_submission TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, id, city) ON rigour_sales_work.temp_sales_checkin_store TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, salesperson_id, employee_code) ON rigour_sales_work.temp_sales_checkin_employee_link TO 'rigour_bi_app'@'%';
GRANT SELECT (tenant_id, store_id, customer_id, customer_code) ON rigour_sales_work.temp_sales_checkin_customer_link TO 'rigour_bi_app'@'%';
