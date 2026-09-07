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

GRANT SELECT ON rigour_erp.erp_stock_balance TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_inventory_warehouse TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_product TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_product_variant TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_product_category TO 'rigour_bi_app'@'%';
GRANT SELECT ON rigour_erp.erp_product_brand TO 'rigour_bi_app'@'%';
