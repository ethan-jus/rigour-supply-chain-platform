-- CRM / ERP / Order / Settings 订货宝分段同步后只读核对。
-- 用法：每跑完一个分段同步后执行本脚本；只读，不改数据。

SELECT
    DATABASE() AS current_database,
    @@hostname AS mysql_host,
    @@port AS mysql_port,
    @@version AS mysql_version;

SELECT
    'latest_integration_runs' AS section,
    BIN_TO_UUID(run.id) AS run_id,
    BIN_TO_UUID(run.tenant_id) AS tenant_id,
    BIN_TO_UUID(task.connector_id) AS connector_id,
    task.task_code,
    task.object_type AS task_object_type,
    run.trigger_type,
    run.status,
    run.fetched_count,
    run.accepted_count,
    run.duplicate_count,
    run.rejected_count,
    run.error_code,
    LEFT(run.error_message, 300) AS error_message,
    run.started_at,
    run.finished_at,
    run.updated_at
FROM rigour_integration.integration_sync_run run
JOIN rigour_integration.integration_sync_task task
  ON task.id = run.task_id
ORDER BY run.created_at DESC
LIMIT 20;

SELECT
    'raw_by_object_type' AS section,
    BIN_TO_UUID(connector_id) AS connector_id,
    source_system,
    source_object_type,
    landing_status,
    COUNT(*) AS row_count,
    MAX(received_at) AS latest_received_at
FROM rigour_integration.integration_raw_landing
GROUP BY connector_id, source_system, source_object_type, landing_status
ORDER BY source_object_type, landing_status;

SELECT
    'dhb_product_raw_lifecycle_probe' AS section,
    BIN_TO_UUID(connector_id) AS connector_id,
    source_id,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.coding')) AS goods_code,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.name')) AS goods_name,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.status')) AS source_status,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.status_name')) AS source_status_name,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.putaway')) AS source_putaway,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$._query_status')) AS query_status,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$._query_putaway')) AS query_putaway,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.is_delete')) AS is_delete,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.is_deleted')) AS is_deleted,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.deleted')) AS deleted,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.delete_flag')) AS delete_flag,
    CASE
        WHEN LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.is_delete')), '')) IN ('1', 'true', 't', 'yes', 'y')
          OR LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.is_deleted')), '')) IN ('1', 'true', 't', 'yes', 'y')
          OR LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.deleted')), '')) IN ('1', 'true', 't', 'yes', 'y')
          OR LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.delete_flag')), '')) IN ('1', 'true', 't', 'yes', 'y')
          OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$._query_status')), '')) = 'F'
          OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.status')), '')) = 'F'
          OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.status')), '')) IN ('D', 'DEL', 'DELETED', 'DELETE', 'REMOVED', 'SOURCE_DELETED')
          OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.status_name')), '') LIKE '%删除%'
          OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.status_name')), '') LIKE '%已删%'
          OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.status_name')), '') LIKE '%作废%'
            THEN 'SOURCE_DELETED'
        WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.putaway')), '')) IN ('F', 'FALSE', '0', 'OFF', 'OFF_SHELF')
            THEN 'INACTIVE'
        WHEN JSON_EXTRACT(payload_json, '$.putaway') IS NOT NULL
          OR JSON_EXTRACT(payload_json, '$.status') IS NOT NULL
          OR JSON_EXTRACT(payload_json, '$.status_name') IS NOT NULL
          OR JSON_EXTRACT(payload_json, '$._query_status') IS NOT NULL
          OR JSON_EXTRACT(payload_json, '$._query_putaway') IS NOT NULL
          OR JSON_EXTRACT(payload_json, '$.is_delete') IS NOT NULL
          OR JSON_EXTRACT(payload_json, '$.is_deleted') IS NOT NULL
          OR JSON_EXTRACT(payload_json, '$.deleted') IS NOT NULL
          OR JSON_EXTRACT(payload_json, '$.delete_flag') IS NOT NULL
            THEN 'NORMAL'
        ELSE 'UNKNOWN'
    END AS inferred_lifecycle,
    received_at,
    JSON_KEYS(payload_json) AS raw_keys
FROM rigour_integration.integration_raw_landing
WHERE source_system = 'DHB'
  AND source_object_type = 'PRODUCT_SPU'
ORDER BY inferred_lifecycle DESC, received_at DESC, goods_code
LIMIT 100;

SELECT
    'erp_product_source_presence' AS section,
    source_object_type,
    source_presence,
    source_status,
    source_putaway,
    COUNT(*) AS row_count,
    MAX(synced_at) AS latest_synced_at
FROM rigour_erp.erp_master_source_binding
WHERE source_system = 'DINGHUOBAO'
  AND source_object_type IN ('PRODUCT_SPU', 'PRODUCT_SKU')
GROUP BY source_object_type, source_presence, source_status, source_putaway
ORDER BY source_object_type, source_presence, source_status, source_putaway;

SELECT
    'erp_product_lifecycle_rows' AS section,
    deleted,
    shelf_status_code,
    COUNT(*) AS row_count
FROM rigour_erp.erp_product
GROUP BY deleted, shelf_status_code
ORDER BY deleted, shelf_status_code;

SELECT
    'erp_variant_lifecycle_rows' AS section,
    p.deleted AS product_deleted,
    v.deleted AS variant_deleted,
    COUNT(*) AS row_count
FROM rigour_erp.erp_product_variant v
JOIN rigour_erp.erp_product p
  ON p.id = v.product_id
GROUP BY p.deleted, v.deleted
ORDER BY p.deleted, v.deleted;

SELECT
    'mapping_by_object_type' AS section,
    BIN_TO_UUID(connector_id) AS connector_id,
    source_system,
    source_object_type,
    internal_domain,
    internal_object_type,
    mapping_status,
    COUNT(*) AS row_count,
    MAX(last_seen_at) AS latest_seen_at
FROM rigour_integration.integration_external_object_mapping
GROUP BY connector_id, source_system, source_object_type, internal_domain, internal_object_type, mapping_status
ORDER BY source_object_type, internal_domain, internal_object_type, mapping_status;

SELECT
    'open_dead_letters' AS section,
    source_system,
    source_object_type,
    status,
    last_error_code,
    COUNT(*) AS row_count,
    MAX(updated_at) AS latest_updated_at,
    LEFT(GROUP_CONCAT(DISTINCT last_error_message ORDER BY updated_at DESC SEPARATOR ' | '), 500) AS sample_errors
FROM rigour_integration.integration_dead_letter
WHERE status IN ('OPEN', 'REPLAYING')
GROUP BY source_system, source_object_type, status, last_error_code
ORDER BY latest_updated_at DESC;

SELECT
    'open_reconciliation_cases' AS section,
    source_system,
    source_object_type,
    check_type,
    status,
    severity,
    COUNT(*) AS row_count,
    MAX(updated_at) AS latest_updated_at,
    LEFT(GROUP_CONCAT(DISTINCT message ORDER BY updated_at DESC SEPARATOR ' | '), 500) AS sample_messages
FROM rigour_integration.integration_reconciliation_case
WHERE status IN ('OPEN', 'ACKNOWLEDGED')
GROUP BY source_system, source_object_type, check_type, status, severity
ORDER BY FIELD(severity, 'ERROR', 'WARN', 'INFO'), latest_updated_at DESC;

SELECT 'business_table_rows' AS section, 'rigour_erp.erp_product' AS table_name, COUNT(*) AS row_count
FROM rigour_erp.erp_product
UNION ALL
SELECT 'business_table_rows', 'rigour_erp.erp_product_variant', COUNT(*)
FROM rigour_erp.erp_product_variant
UNION ALL
SELECT 'business_table_rows', 'rigour_erp.erp_inventory_warehouse', COUNT(*)
FROM rigour_erp.erp_inventory_warehouse
UNION ALL
SELECT 'business_table_rows', 'rigour_crm.crm_customer', COUNT(*)
FROM rigour_crm.crm_customer
UNION ALL
SELECT 'business_table_rows', 'rigour_erp.erp_stock_out_order', COUNT(*)
FROM rigour_erp.erp_stock_out_order
UNION ALL
SELECT 'business_table_rows', 'rigour_erp.erp_transfer_order', COUNT(*)
FROM rigour_erp.erp_transfer_order
UNION ALL
SELECT 'business_table_rows', 'rigour_order.order_sales_order', COUNT(*)
FROM rigour_order.order_sales_order
UNION ALL
SELECT 'business_table_rows', 'rigour_order.order_sales_shipment', COUNT(*)
FROM rigour_order.order_sales_shipment
UNION ALL
SELECT 'business_table_rows', 'rigour_order.order_fund_document', COUNT(*)
FROM rigour_order.order_fund_document;

SELECT
    'duplicate_order_source_order_no' AS section,
    tenant_id,
    source_system_code,
    source_order_no,
    COUNT(*) AS duplicate_count
FROM rigour_order.order_sales_order
WHERE source_system_code IS NOT NULL
  AND source_order_no IS NOT NULL
  AND deleted = 0
GROUP BY tenant_id, source_system_code, source_order_no
HAVING COUNT(*) > 1;

SELECT
    'duplicate_erp_stock_out_source_document' AS section,
    tenant_id,
    connector_id,
    source_system_code,
    source_document_no,
    COUNT(*) AS duplicate_count
FROM rigour_erp.erp_stock_out_order
WHERE source_system_code IS NOT NULL
  AND source_document_no IS NOT NULL
  AND deleted = 0
GROUP BY tenant_id, connector_id, source_system_code, source_document_no
HAVING COUNT(*) > 1;

SELECT
    'duplicate_erp_transfer_source_document' AS section,
    tenant_id,
    connector_id,
    source_system_code,
    source_document_no,
    COUNT(*) AS duplicate_count
FROM rigour_erp.erp_transfer_order
WHERE source_system_code IS NOT NULL
  AND source_document_no IS NOT NULL
  AND deleted = 0
GROUP BY tenant_id, connector_id, source_system_code, source_document_no
HAVING COUNT(*) > 1;

SELECT
    'unresolved_crm_customer_reference' AS section,
    COUNT(*) AS row_count
FROM rigour_crm.crm_customer customer
LEFT JOIN rigour_crm.crm_customer_type customer_type
  ON BIN_TO_UUID(customer_type.tenant_id) = customer.tenant_id
 AND customer_type.type_code = customer.customer_type_code
LEFT JOIN rigour_crm.crm_customer_area customer_area
  ON BIN_TO_UUID(customer_area.tenant_id) = customer.tenant_id
 AND customer_area.area_code = customer.region_code
WHERE customer.deleted = 0
  AND (
      (customer.customer_type_code IS NOT NULL AND customer_type.id IS NULL)
      OR (customer.region_code IS NOT NULL AND customer_area.id IS NULL)
  );
