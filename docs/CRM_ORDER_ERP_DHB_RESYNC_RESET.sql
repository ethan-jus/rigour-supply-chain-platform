-- CRM / Order / ERP / Integration / Business Settings 订货宝重拉前数据清空脚本。
--
-- 适用场景：
-- 1. 已明确执行环境，并确认不是生产误操作；
-- 2. 已暂停 Integration 统一同步调度和人工同步入口；
-- 3. 已备份 rigour_crm、rigour_order、rigour_erp、rigour_integration、rigour_settings；
-- 4. 准备清空可由订货宝全量同步重建的数据，再重新跑统一同步。
--
-- 本脚本不清空 flyway_schema_history，不删除连接器、同步任务、字段映射配置。
-- 本脚本会清空 data_dictionary / data_dictionary_item，必须紧接执行
-- CRM_ORDER_ERP_SETTINGS_DICTIONARY_RESEED.sql 后再跑同步。
-- 本脚本不 DROP 表；旧表物理删除使用 CRM_ORDER_ERP_UNUSED_TABLE_DROP.sql。

SELECT 'BEFORE', table_schema, table_name, table_rows
  FROM information_schema.tables
 WHERE table_schema IN ('rigour_crm', 'rigour_order', 'rigour_erp', 'rigour_integration', 'rigour_settings')
   AND table_type = 'BASE TABLE'
 ORDER BY table_schema, table_name;

SET FOREIGN_KEY_CHECKS = 0;

USE rigour_integration;
DELETE FROM integration_connector_sync_lease;
DELETE FROM integration_dead_letter;
DELETE FROM integration_reconciliation_case;
DELETE FROM integration_outbox_event;
DELETE FROM integration_product_media_item;
DELETE FROM integration_product_media_job;
DELETE FROM integration_order_mirror;
DELETE FROM integration_raw_landing;
DELETE FROM integration_external_object_mapping;
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

SET FOREIGN_KEY_CHECKS = 1;

SELECT 'AFTER', table_schema, table_name, table_rows
  FROM information_schema.tables
 WHERE table_schema IN ('rigour_crm', 'rigour_order', 'rigour_erp', 'rigour_integration', 'rigour_settings')
   AND table_type = 'BASE TABLE'
 ORDER BY table_schema, table_name;
