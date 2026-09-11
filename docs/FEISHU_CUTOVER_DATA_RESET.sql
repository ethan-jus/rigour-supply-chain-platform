-- 飞书导入切换前数据清理脚本。
--
-- 适用场景：
-- 1. 已明确连接的是共享 DEV / 测试库，不是生产库；
-- 2. 已在运行配置或 Nacos 中关闭 Integration 统一订货宝定时编排：
--    RIGOUR_DHB_SYNC_ORCHESTRATION_ENABLED=false；
-- 3. IAM 已完成飞书导入相关迁移，至少包含 V78～V80；
-- 4. 已备份 rigour_crm、rigour_order、rigour_erp、rigour_settings、rigour_iam；
-- 5. 准备清空 CRM / Order / ERP / 业务字典数据，并先清理 IAM 订货宝人员，再导入飞书导出文件。
--
-- IAM 只清理订货宝同步产生的人员主档和人员绑定，不删除 iam_user、角色、资源、租户和组织。
-- 本脚本不清空 flyway_schema_history，不删除订货宝连接器和同步任务配置。
-- 清空业务字典后必须紧接执行 CRM_ORDER_ERP_SETTINGS_DICTIONARY_RESEED.sql。

SELECT DATABASE() AS current_database,
       @@hostname AS mysql_host,
       @@port AS mysql_port,
       @@version AS mysql_version,
       @@read_only AS read_only,
       @@super_read_only AS super_read_only;

SELECT 'BEFORE' AS stage, table_schema, table_name, table_rows
  FROM information_schema.tables
 WHERE table_schema IN ('rigour_crm', 'rigour_order', 'rigour_erp', 'rigour_settings', 'rigour_iam')
   AND table_type = 'BASE TABLE'
 ORDER BY table_schema, table_name;

SET @cleanup_at = NOW(6);

SET FOREIGN_KEY_CHECKS = 0;

USE rigour_integration;
UPDATE integration_sync_task task
JOIN integration_dhb_connector connector
  ON connector.tenant_id = task.tenant_id
 AND connector.id = task.connector_id
 AND connector.deleted_at IS NULL
   SET task.enabled = 0,
       task.task_status = 'PAUSED',
       task.next_run_at = NULL,
       task.updated_at = @cleanup_at
 WHERE task.deleted_at IS NULL
   AND connector.status = 'ACTIVE'
   AND task.object_type IN (
       'BUSINESS_DICTIONARY',
       'PRODUCT_MASTER_DATA',
       'CRM_MASTER_DATA',
       'SUPPLY_CHAIN_DATA',
       'ORDER'
   );

DELETE FROM integration_connector_sync_lease;
DELETE FROM integration_dead_letter;
DELETE FROM integration_reconciliation_case;
DELETE FROM integration_outbox_event;
DELETE FROM integration_product_media_item;
DELETE FROM integration_product_media_job;
DELETE FROM integration_feishu_import_issue;
DELETE FROM integration_feishu_import_raw_row;
DELETE FROM integration_feishu_import_table;
DELETE FROM integration_feishu_import_batch;
DELETE FROM integration_order_mirror;
DELETE FROM integration_raw_landing WHERE source_system IN ('DHB', 'DINGHUOBAO');
DELETE FROM integration_external_object_mapping WHERE source_system IN ('DHB', 'DINGHUOBAO');
DELETE FROM integration_sync_log;
DELETE FROM integration_sync_checkpoint;
DELETE FROM integration_sync_run;

USE rigour_order;
DELETE FROM order_sales_shipment_line;
DELETE FROM order_sales_shipment;
DELETE FROM order_refund_record;
DELETE FROM order_fund_document;
DELETE FROM order_payment_record;
DELETE FROM order_sales_order_line;
DELETE FROM order_sales_order;

ALTER TABLE order_sales_shipment_line AUTO_INCREMENT = 1;
ALTER TABLE order_sales_shipment AUTO_INCREMENT = 1;
ALTER TABLE order_refund_record AUTO_INCREMENT = 1;
ALTER TABLE order_fund_document AUTO_INCREMENT = 1;
ALTER TABLE order_payment_record AUTO_INCREMENT = 1;
ALTER TABLE order_sales_order_line AUTO_INCREMENT = 1;
ALTER TABLE order_sales_order AUTO_INCREMENT = 1;

USE rigour_erp;
DELETE FROM erp_transfer_order_line;
DELETE FROM erp_stock_out_order_line;
DELETE FROM erp_stock_in_order_line;
DELETE FROM erp_procurement_order_line;
DELETE FROM erp_purchase_return_order_line;
DELETE FROM erp_stock_flow;
DELETE FROM erp_stock_balance;
DELETE FROM erp_transfer_order;
DELETE FROM erp_stock_out_order;
DELETE FROM erp_stock_in_order;
DELETE FROM erp_procurement_order;
DELETE FROM erp_purchase_return_order;
DELETE FROM erp_product_variant;
DELETE FROM erp_product;
DELETE FROM erp_product_specification_value;
DELETE FROM erp_product_specification;
DELETE FROM erp_product_category;
DELETE FROM erp_product_brand;
DELETE FROM erp_product_tag;
DELETE FROM erp_supplier_profile;
DELETE FROM erp_inventory_warehouse;
DELETE FROM erp_master_source_binding;
DELETE FROM erp_master_data_sync_checkpoint;
DELETE FROM erp_master_data_sync_lock;
DELETE FROM erp_master_data_sync_run;

ALTER TABLE erp_transfer_order_line AUTO_INCREMENT = 1;
ALTER TABLE erp_stock_out_order_line AUTO_INCREMENT = 1;
ALTER TABLE erp_stock_in_order_line AUTO_INCREMENT = 1;
ALTER TABLE erp_procurement_order_line AUTO_INCREMENT = 1;
ALTER TABLE erp_purchase_return_order_line AUTO_INCREMENT = 1;
ALTER TABLE erp_stock_flow AUTO_INCREMENT = 1;
ALTER TABLE erp_stock_balance AUTO_INCREMENT = 1;
ALTER TABLE erp_transfer_order AUTO_INCREMENT = 1;
ALTER TABLE erp_stock_out_order AUTO_INCREMENT = 1;
ALTER TABLE erp_stock_in_order AUTO_INCREMENT = 1;
ALTER TABLE erp_procurement_order AUTO_INCREMENT = 1;
ALTER TABLE erp_purchase_return_order AUTO_INCREMENT = 1;
ALTER TABLE erp_product_variant AUTO_INCREMENT = 1;
ALTER TABLE erp_product AUTO_INCREMENT = 1;
ALTER TABLE erp_product_specification_value AUTO_INCREMENT = 1;
ALTER TABLE erp_product_specification AUTO_INCREMENT = 1;
ALTER TABLE erp_product_category AUTO_INCREMENT = 1;
ALTER TABLE erp_product_brand AUTO_INCREMENT = 1;
ALTER TABLE erp_product_tag AUTO_INCREMENT = 1;
ALTER TABLE erp_supplier_profile AUTO_INCREMENT = 1;
ALTER TABLE erp_inventory_warehouse AUTO_INCREMENT = 1;
ALTER TABLE erp_master_data_sync_run AUTO_INCREMENT = 1;

USE rigour_crm;
DELETE FROM crm_source_identity_alias;
DELETE FROM crm_source_binding;
DELETE FROM crm_sync_lock;
DELETE FROM crm_sync_checkpoint;
DELETE FROM crm_sync_run;
DELETE FROM crm_sales_assignment;
DELETE FROM crm_address;
DELETE FROM crm_contact;
DELETE FROM crm_customer_policy;
DELETE FROM crm_customer_profile;
DELETE FROM crm_party_role;
DELETE FROM crm_customer;
DELETE FROM crm_party;
DELETE FROM crm_customer_area;
DELETE FROM crm_customer_type;

ALTER TABLE crm_customer AUTO_INCREMENT = 1;

USE rigour_settings;
DELETE FROM data_dictionary_item;
DELETE FROM data_dictionary;

ALTER TABLE data_dictionary_item AUTO_INCREMENT = 1;
ALTER TABLE data_dictionary AUTO_INCREMENT = 1;

USE rigour_iam;
CREATE TEMPORARY TABLE tmp_feishu_cutover_dhb_staff (
    tenant_id BINARY(16) NOT NULL,
    staff_id  BINARY(16) NOT NULL,
    PRIMARY KEY (tenant_id, staff_id)
) ENGINE=MEMORY;

INSERT IGNORE INTO tmp_feishu_cutover_dhb_staff (tenant_id, staff_id)
SELECT tenant_id, id
  FROM iam_staff_profile
 WHERE record_origin = 'DINGHUOBAO'
   AND deleted_at IS NULL;

UPDATE iam_staff_user_binding binding
JOIN tmp_feishu_cutover_dhb_staff target
  ON target.tenant_id = binding.tenant_id
 AND target.staff_id = binding.staff_id
   SET binding.status = 'INACTIVE',
       binding.updated_at = @cleanup_at,
       binding.deleted_at = COALESCE(binding.deleted_at, @cleanup_at),
       binding.delete_reason = COALESCE(binding.delete_reason, 'FEISHU_CUTOVER_DHB_STAFF_RESET')
 WHERE binding.deleted_at IS NULL;

UPDATE iam_staff_assignment assignment
JOIN tmp_feishu_cutover_dhb_staff target
  ON target.tenant_id = assignment.tenant_id
 AND target.staff_id = assignment.staff_id
   SET assignment.status = 'INACTIVE',
       assignment.effective_to = COALESCE(assignment.effective_to, @cleanup_at),
       assignment.updated_at = @cleanup_at,
       assignment.deleted_at = COALESCE(assignment.deleted_at, @cleanup_at),
       assignment.delete_reason = COALESCE(assignment.delete_reason, 'FEISHU_CUTOVER_DHB_STAFF_RESET')
 WHERE assignment.deleted_at IS NULL;

UPDATE iam_external_staff_binding external_binding
   SET external_binding.source_presence = 'MISSING',
       external_binding.updated_at = @cleanup_at,
       external_binding.deleted_at = COALESCE(external_binding.deleted_at, @cleanup_at),
       external_binding.delete_reason = COALESCE(external_binding.delete_reason, 'FEISHU_CUTOVER_DHB_STAFF_RESET')
 WHERE external_binding.source_system = 'DINGHUOBAO'
   AND external_binding.deleted_at IS NULL;

UPDATE iam_staff_profile staff
JOIN tmp_feishu_cutover_dhb_staff target
  ON target.tenant_id = staff.tenant_id
 AND target.staff_id = staff.id
   SET staff.employment_status = 'LEFT',
       staff.updated_at = @cleanup_at,
       staff.deleted_at = COALESCE(staff.deleted_at, @cleanup_at),
       staff.delete_reason = COALESCE(staff.delete_reason, 'FEISHU_CUTOVER_DHB_STAFF_RESET')
 WHERE staff.record_origin = 'DINGHUOBAO'
   AND staff.deleted_at IS NULL;

DROP TEMPORARY TABLE tmp_feishu_cutover_dhb_staff;

SET FOREIGN_KEY_CHECKS = 1;

SELECT 'AFTER' AS stage, table_schema, table_name, table_rows
  FROM information_schema.tables
 WHERE table_schema IN ('rigour_crm', 'rigour_order', 'rigour_erp', 'rigour_settings', 'rigour_iam')
   AND table_type = 'BASE TABLE'
 ORDER BY table_schema, table_name;

SELECT 'dhb_sync_task_status' AS section,
       BIN_TO_UUID(task.tenant_id) AS tenant_id,
       BIN_TO_UUID(task.connector_id) AS connector_id,
       task.task_code,
       task.object_type,
       task.task_status,
       task.enabled,
       task.next_run_at,
       task.updated_at
  FROM rigour_integration.integration_sync_task task
  JOIN rigour_integration.integration_dhb_connector connector
    ON connector.tenant_id = task.tenant_id
   AND connector.id = task.connector_id
   AND connector.deleted_at IS NULL
 WHERE task.deleted_at IS NULL
   AND connector.status = 'ACTIVE'
 ORDER BY task.object_type, task.task_code;

SELECT 'remaining_dhb_staff' AS section,
       COUNT(*) AS active_dhb_staff_count
  FROM rigour_iam.iam_staff_profile
 WHERE record_origin = 'DINGHUOBAO'
   AND deleted_at IS NULL;
