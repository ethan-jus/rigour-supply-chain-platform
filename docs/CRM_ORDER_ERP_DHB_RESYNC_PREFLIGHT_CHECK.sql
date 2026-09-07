-- CRM / Order / ERP / Settings 订货宝重同步只读预检脚本。
--
-- 执行时机：
-- 1. 已确认连接到共享 DEV/测试 MySQL，不是生产；
-- 2. ERP Flyway 已包含 V26__erp_connector_scoped_source_identity.sql；
-- 3. 清库、废表 DROP、分段同步之前先执行本脚本留存结果。
--
-- 本脚本只读，不修改任何业务数据。

SELECT DATABASE() AS current_database,
       @@hostname AS mysql_host,
       @@port AS mysql_port,
       @@version AS mysql_version,
       @@read_only AS read_only,
       @@super_read_only AS super_read_only;

SELECT 'connector_status' AS section,
       status,
       COUNT(*) AS connector_count
  FROM rigour_integration.integration_dhb_connector
 WHERE deleted_at IS NULL
 GROUP BY status
 ORDER BY status;

SELECT 'sync_task_status' AS section,
       task_status,
       enabled,
       COUNT(*) AS task_count
  FROM rigour_integration.integration_sync_task
 WHERE deleted_at IS NULL
 GROUP BY task_status, enabled
 ORDER BY task_status, enabled;

SELECT 'active_lease' AS section,
       BIN_TO_UUID(tenant_id) AS tenant_id,
       BIN_TO_UUID(connector_id) AS connector_id,
       lease_token,
       owner_id,
       expires_at
  FROM rigour_integration.integration_connector_sync_lease
 WHERE expires_at > NOW(6)
 ORDER BY expires_at DESC;

SELECT 'running_integration_run' AS section,
       BIN_TO_UUID(run.tenant_id) AS tenant_id,
       BIN_TO_UUID(task.connector_id) AS connector_id,
       BIN_TO_UUID(run.task_id) AS task_id,
       BIN_TO_UUID(run.id) AS run_id,
       run.status,
       run.started_at,
       run.updated_at
  FROM rigour_integration.integration_sync_run run
  JOIN rigour_integration.integration_sync_task task
    ON task.tenant_id = run.tenant_id
   AND task.id = run.task_id
 WHERE run.status = 'RUNNING'
 ORDER BY run.started_at DESC;

SELECT 'running_erp_run' AS section,
       tenant_id,
       connector_id,
       id AS run_id,
       object_type,
       status,
       started_at,
       updated_time
  FROM rigour_erp.erp_master_data_sync_run
 WHERE status = 'RUNNING'
 ORDER BY started_at DESC;

SELECT 'dictionary_count' AS section,
       dictionary.dictionary_code,
       COUNT(item.id) AS item_count
  FROM rigour_settings.data_dictionary dictionary
  LEFT JOIN rigour_settings.data_dictionary_item item
    ON item.dictionary_code = dictionary.dictionary_code
   AND item.deleted = 0
 WHERE dictionary.deleted = 0
 GROUP BY dictionary.dictionary_code
 ORDER BY dictionary.dictionary_code;

SELECT 'blocking_dictionary_gap' AS section,
       required.dictionary_code,
       COALESCE(actual.item_count, 0) AS item_count,
       required.reason
  FROM (
      SELECT 'CUSTOMER_STATUS' AS dictionary_code, 'CRM客户状态筛选和同步状态归一化必需' AS reason
      UNION ALL SELECT 'PRODUCT_UNIT', 'ERP商品单位和订单/供应链明细单位必需'
      UNION ALL SELECT 'DHB_UNIT', '订货宝来源单位原始值映射必需'
      UNION ALL SELECT 'DHB_PRODUCT_STATUS', '订货宝商品状态来源值对账必需'
      UNION ALL SELECT 'DHB_PRODUCT_PUTAWAY', '订货宝商品上下架来源值对账必需'
      UNION ALL SELECT 'DHB_CUSTOMER_STATUS', '订货宝客户状态来源值对账必需'
      UNION ALL SELECT 'DHB_SHIPMENT_TYPE', '订货宝出库类型投影规则必需'
      UNION ALL SELECT 'STOCK_OUT_TYPE', 'ERP统一出库类型落库必需'
      UNION ALL SELECT 'DHB_WAREHOUSE_STATUS', '订货宝仓库状态来源值对账必需'
  ) required
  LEFT JOIN (
      SELECT dictionary.dictionary_code,
             COUNT(item.id) AS item_count
        FROM rigour_settings.data_dictionary dictionary
        LEFT JOIN rigour_settings.data_dictionary_item item
          ON item.dictionary_code = dictionary.dictionary_code
         AND item.deleted = 0
       WHERE dictionary.deleted = 0
       GROUP BY dictionary.dictionary_code
  ) actual ON actual.dictionary_code = required.dictionary_code
 WHERE COALESCE(actual.item_count, 0) = 0
 ORDER BY required.dictionary_code;

SELECT 'business_table_rows' AS section, 'rigour_crm.crm_customer' AS table_name, COUNT(*) AS row_count
  FROM rigour_crm.crm_customer
UNION ALL SELECT 'business_table_rows', 'rigour_order.order_sales_order', COUNT(*)
  FROM rigour_order.order_sales_order
UNION ALL SELECT 'business_table_rows', 'rigour_order.order_sales_shipment', COUNT(*)
  FROM rigour_order.order_sales_shipment
UNION ALL SELECT 'business_table_rows', 'rigour_order.order_fund_document', COUNT(*)
  FROM rigour_order.order_fund_document
UNION ALL SELECT 'business_table_rows', 'rigour_erp.erp_product', COUNT(*)
  FROM rigour_erp.erp_product
UNION ALL SELECT 'business_table_rows', 'rigour_erp.erp_product_variant', COUNT(*)
  FROM rigour_erp.erp_product_variant
UNION ALL SELECT 'business_table_rows', 'rigour_erp.erp_inventory_warehouse', COUNT(*)
  FROM rigour_erp.erp_inventory_warehouse
UNION ALL SELECT 'business_table_rows', 'rigour_erp.erp_stock_out_order', COUNT(*)
  FROM rigour_erp.erp_stock_out_order
UNION ALL SELECT 'business_table_rows', 'rigour_erp.erp_transfer_order', COUNT(*)
  FROM rigour_erp.erp_transfer_order
UNION ALL SELECT 'business_table_rows', 'rigour_integration.integration_raw_landing', COUNT(*)
  FROM rigour_integration.integration_raw_landing
UNION ALL SELECT 'business_table_rows', 'rigour_integration.integration_external_object_mapping', COUNT(*)
  FROM rigour_integration.integration_external_object_mapping
ORDER BY table_name;

SELECT 'duplicate_external_mapping' AS section,
       BIN_TO_UUID(tenant_id) AS tenant_id,
       BIN_TO_UUID(connector_id) AS connector_id,
       source_system,
       source_object_type,
       source_object_id,
       COUNT(*) AS duplicate_count
  FROM rigour_integration.integration_external_object_mapping
 WHERE deleted_at IS NULL
 GROUP BY tenant_id, connector_id, source_system, source_object_type, source_object_id
HAVING COUNT(*) > 1
 ORDER BY duplicate_count DESC, source_object_type, source_object_id
 LIMIT 100;

SELECT 'duplicate_erp_stock_out_source' AS section,
       tenant_id,
       connector_id,
       source_system_code,
       source_document_no,
       COUNT(*) AS duplicate_count
  FROM rigour_erp.erp_stock_out_order
 WHERE deleted = 0
   AND connector_id IS NOT NULL
   AND source_system_code IS NOT NULL
   AND source_document_no IS NOT NULL
 GROUP BY tenant_id, connector_id, source_system_code, source_document_no
HAVING COUNT(*) > 1
 ORDER BY duplicate_count DESC, source_document_no
 LIMIT 100;

SELECT 'duplicate_erp_transfer_source' AS section,
       tenant_id,
       connector_id,
       source_system_code,
       source_document_no,
       COUNT(*) AS duplicate_count
  FROM rigour_erp.erp_transfer_order
 WHERE deleted = 0
   AND connector_id IS NOT NULL
   AND source_system_code IS NOT NULL
   AND source_document_no IS NOT NULL
 GROUP BY tenant_id, connector_id, source_system_code, source_document_no
HAVING COUNT(*) > 1
 ORDER BY duplicate_count DESC, source_document_no
 LIMIT 100;

SELECT 'duplicate_order_source' AS section,
       tenant_id,
       source_system_code,
       source_order_no,
       COUNT(*) AS duplicate_count
  FROM rigour_order.order_sales_order
 WHERE deleted = 0
   AND source_system_code IS NOT NULL
   AND source_order_no IS NOT NULL
 GROUP BY tenant_id, source_system_code, source_order_no
HAVING COUNT(*) > 1
 ORDER BY duplicate_count DESC, source_order_no
 LIMIT 100;
