-- 订货宝同步到新业务模型后的只读审计脚本。
--
-- 使用方式：
-- 1. 在 IDEA Database Console 连接 `rigour` 数据源后执行本文件。
-- 2. 本文件默认只做 SELECT，不会修改数据。
-- 3. 修复重复数据前必须先核对 Integration Raw、来源映射和业务影响。
--
-- 审计目标：
-- - 订货宝来源数据是否只映射到我方 ERP/CRM/Order/字典新业务表。
-- - 重复同步是否复用来源绑定，而不是新增业务记录。
-- - 销售订单头数量是否等于当前有效明细汇总数量。
-- - CRM 客户类型、归属地区是否已落到我方客户主档。

-- 1. CRM：客户类型/归属地区缺失统计。
USE rigour_crm;

SELECT 'crm_customer_missing_type_region' AS audit_item,
       COUNT(*) AS total_count,
       SUM(customer_type_code IS NULL OR customer_type_code = '') AS missing_customer_type_count,
       SUM(region_code IS NULL OR region_code = '') AS missing_region_count
  FROM crm_customer
 WHERE deleted = 0;

-- 2. CRM：客户类型/地区 code 无法关联到新主数据。
SELECT 'crm_customer_type_code_unmatched' AS audit_item,
       c.customer_type_code,
       COUNT(*) AS customer_count
  FROM crm_customer c
  LEFT JOIN crm_customer_type t
    ON t.tenant_id = c.tenant_id
   AND t.type_code = c.customer_type_code
   AND t.deleted_at IS NULL
 WHERE c.deleted = 0
   AND c.customer_type_code IS NOT NULL
   AND c.customer_type_code <> ''
   AND t.id IS NULL
 GROUP BY c.customer_type_code
 ORDER BY customer_count DESC;

SELECT 'crm_customer_region_code_unmatched' AS audit_item,
       c.region_code,
       COUNT(*) AS customer_count
  FROM crm_customer c
  LEFT JOIN crm_customer_area a
    ON a.tenant_id = c.tenant_id
   AND a.area_code = c.region_code
   AND a.deleted_at IS NULL
 WHERE c.deleted = 0
   AND c.region_code IS NOT NULL
   AND c.region_code <> ''
   AND a.id IS NULL
 GROUP BY c.region_code
 ORDER BY customer_count DESC;

-- 3. CRM：按来源绑定检查同一订货宝客户是否映射多个我方客户。
SELECT 'crm_source_customer_duplicate_mapping' AS audit_item,
       HEX(tenant_id) AS tenant_id_hex,
       HEX(connector_id) AS connector_id_hex,
       source_object_type,
       source_object_id,
       COUNT(*) AS mapping_count,
       GROUP_CONCAT(CAST(target_id AS CHAR) ORDER BY updated_at DESC) AS target_ids
  FROM crm_source_binding
 WHERE source_system IN ('DHB', 'DINGHUOBAO')
   AND source_object_type = 'CUSTOMER'
 GROUP BY tenant_id, connector_id, source_object_type, source_object_id
HAVING COUNT(*) > 1;

-- 3.1 CRM：历史旧口径把订货宝来源 ID 直接写进客户类型/地区业务 code 的记录。
SELECT 'crm_customer_type_legacy_source_id_code' AS audit_item,
       t.type_code,
       t.type_name,
       b.source_object_id,
       b.source_code,
       b.synced_at
  FROM crm_customer_type t
  JOIN crm_source_binding b
    ON b.tenant_id = t.tenant_id
   AND b.target_id = t.id
   AND b.source_system IN ('DHB', 'DINGHUOBAO')
   AND b.source_object_type = 'CUSTOMER_TYPE'
 WHERE t.deleted_at IS NULL
   AND t.type_code = b.source_object_id
 ORDER BY b.synced_at DESC;

SELECT 'crm_customer_area_legacy_source_id_code' AS audit_item,
       a.area_code,
       a.area_name,
       b.source_object_id,
       b.source_code,
       b.synced_at
  FROM crm_customer_area a
  JOIN crm_source_binding b
    ON b.tenant_id = a.tenant_id
   AND b.target_id = a.id
   AND b.source_system IN ('DHB', 'DINGHUOBAO')
   AND b.source_object_type = 'CUSTOMER_AREA'
 WHERE a.deleted_at IS NULL
   AND a.area_code = b.source_object_id
 ORDER BY b.synced_at DESC;

-- 4. Order：销售订单头数量与有效明细汇总不一致。
USE rigour_order;

SELECT 'order_sales_order_quantity_mismatch' AS audit_item,
       o.tenant_id,
       o.id,
       o.order_no,
       o.customer_name_snapshot,
       o.total_quantity AS header_quantity,
       COALESCE(SUM(l.quantity), 0) AS line_quantity,
       COUNT(l.id) AS active_line_count,
       o.updated_time
  FROM order_sales_order o
  LEFT JOIN order_sales_order_line l
    ON l.tenant_id = o.tenant_id
   AND l.order_id = o.id
   AND l.deleted = 0
 WHERE o.deleted = 0
 GROUP BY o.tenant_id, o.id, o.order_no, o.customer_name_snapshot, o.total_quantity, o.updated_time
HAVING o.total_quantity <> COALESCE(SUM(l.quantity), 0)
 ORDER BY o.updated_time DESC;

-- 5. Order：疑似重复销售订单。真实处理必须回看 Integration 外部映射与订货宝网页订货单。
SELECT 'order_sales_order_possible_duplicate' AS audit_item,
       tenant_id,
       customer_name_snapshot,
       contact_phone_snapshot,
       order_date,
       payable_amount,
       total_quantity,
       COUNT(*) AS duplicate_count,
       GROUP_CONCAT(order_no ORDER BY updated_time DESC) AS order_nos
  FROM order_sales_order
 WHERE deleted = 0
 GROUP BY tenant_id, customer_name_snapshot, contact_phone_snapshot, order_date, payable_amount, total_quantity
HAVING COUNT(*) > 1
 ORDER BY duplicate_count DESC, order_date DESC;

-- 6. Integration：同一订货宝订货单是否映射到多个我方销售订单。
USE rigour_integration;

SELECT 'integration_sales_order_duplicate_mapping' AS audit_item,
       HEX(tenant_id) AS tenant_id_hex,
       HEX(connector_id) AS connector_id_hex,
       source_object_type,
       source_object_id,
       source_object_no,
       COUNT(*) AS mapping_count,
       GROUP_CONCAT(internal_object_no ORDER BY updated_at DESC) AS internal_object_nos
  FROM integration_external_object_mapping
 WHERE source_system IN ('DHB', 'DINGHUOBAO')
   AND source_object_type IN ('SALES_ORDER', 'ORDER')
   AND mapping_status = 'ACTIVE'
   AND deleted_at IS NULL
 GROUP BY tenant_id, connector_id, source_object_type, source_object_id, source_object_no
HAVING COUNT(*) > 1;

-- 7. ERP：来源绑定异常和一源多目标检查。
USE rigour_erp;

SELECT 'erp_source_binding_blank_source_id' AS audit_item,
       tenant_id,
       source_object_type,
       target_type,
       COUNT(*) AS row_count
  FROM erp_master_source_binding
 WHERE source_system IN ('DHB', 'DINGHUOBAO')
   AND (source_object_id IS NULL OR source_object_id = '')
 GROUP BY tenant_id, source_object_type, target_type;

SELECT 'erp_source_binding_possible_duplicate_target' AS audit_item,
       tenant_id,
       source_object_type,
       source_object_id,
       COUNT(*) AS mapping_count,
       GROUP_CONCAT(CONCAT(target_type, ':', target_id) ORDER BY updated_at DESC) AS targets
FROM erp_master_source_binding
WHERE source_system IN ('DHB', 'DINGHUOBAO')
 GROUP BY tenant_id, source_object_type, source_object_id
HAVING COUNT(*) > 1;

-- 8. ERP：商品、规格、供应商、仓库、采购/入库/退货是否存在疑似业务重复。
SELECT 'erp_product_possible_duplicate' AS audit_item,
       tenant_id,
       product_name,
       unit_code,
       COUNT(*) AS duplicate_count,
       GROUP_CONCAT(product_code ORDER BY updated_time DESC) AS product_codes
  FROM erp_product
 WHERE deleted = 0
 GROUP BY tenant_id, product_name, unit_code
HAVING COUNT(*) > 1
 ORDER BY duplicate_count DESC;

SELECT 'erp_variant_possible_duplicate' AS audit_item,
       tenant_id,
       product_id,
       specification_snapshot,
       sale_price,
       COUNT(*) AS duplicate_count,
       GROUP_CONCAT(variant_code ORDER BY updated_time DESC) AS variant_codes
  FROM erp_product_variant
 WHERE deleted = 0
 GROUP BY tenant_id, product_id, specification_snapshot, sale_price
HAVING COUNT(*) > 1
 ORDER BY duplicate_count DESC;

-- 9. 单结果集汇总：用于 IDEA Result 一次复制关键审计结果。
--    仍然只做 SELECT，detail 为 JSON 文本，便于排查和留档。
SELECT 'crm_customer_missing_type_region' AS audit_item,
       'ALL' AS ref_key,
       COUNT(*) AS row_count,
       CAST(JSON_OBJECT(
           'total', COUNT(*),
           'missingCustomerType', SUM(customer_type_code IS NULL OR customer_type_code = ''),
           'missingRegion', SUM(region_code IS NULL OR region_code = '')
       ) AS CHAR) AS detail
  FROM rigour_crm.crm_customer
 WHERE deleted = 0
UNION ALL
SELECT 'crm_customer_type_code_unmatched' AS audit_item,
       q.customer_type_code AS ref_key,
       q.customer_count AS row_count,
       NULL AS detail
  FROM (
        SELECT c.customer_type_code, COUNT(*) AS customer_count
          FROM rigour_crm.crm_customer c
          LEFT JOIN rigour_crm.crm_customer_type t
            ON t.tenant_id = c.tenant_id
           AND t.type_code = c.customer_type_code
           AND t.deleted_at IS NULL
         WHERE c.deleted = 0
           AND c.customer_type_code IS NOT NULL
           AND c.customer_type_code <> ''
           AND t.id IS NULL
         GROUP BY c.customer_type_code
         ORDER BY customer_count DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'crm_customer_region_code_unmatched' AS audit_item,
       q.region_code AS ref_key,
       q.customer_count AS row_count,
       NULL AS detail
  FROM (
        SELECT c.region_code, COUNT(*) AS customer_count
          FROM rigour_crm.crm_customer c
          LEFT JOIN rigour_crm.crm_customer_area a
            ON a.tenant_id = c.tenant_id
           AND a.area_code = c.region_code
           AND a.deleted_at IS NULL
         WHERE c.deleted = 0
           AND c.region_code IS NOT NULL
           AND c.region_code <> ''
           AND a.id IS NULL
         GROUP BY c.region_code
         ORDER BY customer_count DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'crm_source_customer_duplicate_mapping' AS audit_item,
       q.source_object_id AS ref_key,
       q.mapping_count AS row_count,
       q.target_ids AS detail
  FROM (
        SELECT source_object_id,
               COUNT(*) AS mapping_count,
               GROUP_CONCAT(CAST(target_id AS CHAR) ORDER BY updated_at DESC) AS target_ids
          FROM rigour_crm.crm_source_binding
         WHERE source_system IN ('DHB', 'DINGHUOBAO')
           AND source_object_type = 'CUSTOMER'
         GROUP BY tenant_id, connector_id, source_object_type, source_object_id
        HAVING COUNT(*) > 1
         ORDER BY mapping_count DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'crm_customer_type_legacy_source_id_code' AS audit_item,
       q.type_code AS ref_key,
       1 AS row_count,
       q.detail AS detail
  FROM (
        SELECT t.type_code,
               CAST(JSON_OBJECT('typeName', t.type_name, 'sourceObjectId', b.source_object_id, 'sourceCode', b.source_code) AS CHAR) AS detail
          FROM rigour_crm.crm_customer_type t
          JOIN rigour_crm.crm_source_binding b
            ON b.tenant_id = t.tenant_id
           AND b.target_id = t.id
           AND b.source_system IN ('DHB', 'DINGHUOBAO')
           AND b.source_object_type = 'CUSTOMER_TYPE'
         WHERE t.deleted_at IS NULL
           AND t.type_code = b.source_object_id
         ORDER BY b.synced_at DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'crm_customer_area_legacy_source_id_code' AS audit_item,
       q.area_code AS ref_key,
       1 AS row_count,
       q.detail AS detail
  FROM (
        SELECT a.area_code,
               CAST(JSON_OBJECT('areaName', a.area_name, 'sourceObjectId', b.source_object_id, 'sourceCode', b.source_code) AS CHAR) AS detail
          FROM rigour_crm.crm_customer_area a
          JOIN rigour_crm.crm_source_binding b
            ON b.tenant_id = a.tenant_id
           AND b.target_id = a.id
           AND b.source_system IN ('DHB', 'DINGHUOBAO')
           AND b.source_object_type = 'CUSTOMER_AREA'
         WHERE a.deleted_at IS NULL
           AND a.area_code = b.source_object_id
         ORDER BY b.synced_at DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'order_sales_order_quantity_mismatch' AS audit_item,
       q.order_no AS ref_key,
       1 AS row_count,
       CAST(JSON_OBJECT(
           'customer', q.customer_name_snapshot,
           'headerQuantity', q.header_quantity,
           'lineQuantity', q.line_quantity,
           'activeLineCount', q.active_line_count,
           'updatedTime', q.updated_time
       ) AS CHAR) AS detail
  FROM (
        SELECT o.order_no,
               o.customer_name_snapshot,
               o.total_quantity AS header_quantity,
               COALESCE(SUM(l.quantity), 0) AS line_quantity,
               COUNT(l.id) AS active_line_count,
               o.updated_time
          FROM rigour_order.order_sales_order o
          LEFT JOIN rigour_order.order_sales_order_line l
            ON l.tenant_id = o.tenant_id
           AND l.order_id = o.id
           AND l.deleted = 0
         WHERE o.deleted = 0
         GROUP BY o.tenant_id, o.id, o.order_no, o.customer_name_snapshot, o.total_quantity, o.updated_time
        HAVING o.total_quantity <> COALESCE(SUM(l.quantity), 0)
         ORDER BY o.updated_time DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'order_sales_order_possible_duplicate' AS audit_item,
       q.order_nos AS ref_key,
       q.duplicate_count AS row_count,
       CAST(JSON_OBJECT(
           'customer', q.customer_name_snapshot,
           'phone', q.contact_phone_snapshot,
           'orderDate', q.order_date,
           'payableAmount', q.payable_amount,
           'totalQuantity', q.total_quantity
       ) AS CHAR) AS detail
  FROM (
        SELECT customer_name_snapshot,
               contact_phone_snapshot,
               order_date,
               payable_amount,
               total_quantity,
               COUNT(*) AS duplicate_count,
               GROUP_CONCAT(order_no ORDER BY updated_time DESC) AS order_nos
          FROM rigour_order.order_sales_order
         WHERE deleted = 0
         GROUP BY tenant_id, customer_name_snapshot, contact_phone_snapshot, order_date, payable_amount, total_quantity
        HAVING COUNT(*) > 1
         ORDER BY duplicate_count DESC, order_date DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'integration_sales_order_duplicate_mapping' AS audit_item,
       q.source_object_id AS ref_key,
       q.mapping_count AS row_count,
       q.internal_object_nos AS detail
  FROM (
        SELECT source_object_id,
               COUNT(*) AS mapping_count,
               GROUP_CONCAT(internal_object_no ORDER BY updated_at DESC) AS internal_object_nos
          FROM rigour_integration.integration_external_object_mapping
         WHERE source_system IN ('DHB', 'DINGHUOBAO')
           AND source_object_type IN ('SALES_ORDER', 'ORDER')
           AND mapping_status = 'ACTIVE'
           AND deleted_at IS NULL
         GROUP BY tenant_id, connector_id, source_object_type, source_object_id, source_object_no
        HAVING COUNT(*) > 1
         ORDER BY mapping_count DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'erp_source_binding_blank_source_id' AS audit_item,
       CONCAT(q.source_object_type, '/', q.target_type) AS ref_key,
       q.row_count AS row_count,
       NULL AS detail
  FROM (
        SELECT source_object_type, target_type, COUNT(*) AS row_count
          FROM rigour_erp.erp_master_source_binding
         WHERE source_system IN ('DHB', 'DINGHUOBAO')
           AND (source_object_id IS NULL OR source_object_id = '')
         GROUP BY tenant_id, source_object_type, target_type
         ORDER BY row_count DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'erp_source_binding_possible_duplicate_target' AS audit_item,
       q.source_object_id AS ref_key,
       q.mapping_count AS row_count,
       q.targets AS detail
  FROM (
        SELECT source_object_id,
               COUNT(*) AS mapping_count,
               GROUP_CONCAT(CONCAT(target_type, ':', target_id) ORDER BY updated_at DESC) AS targets
          FROM rigour_erp.erp_master_source_binding
         WHERE source_system IN ('DHB', 'DINGHUOBAO')
         GROUP BY tenant_id, source_object_type, source_object_id
        HAVING COUNT(*) > 1
         ORDER BY mapping_count DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'erp_product_possible_duplicate' AS audit_item,
       q.product_name AS ref_key,
       q.duplicate_count AS row_count,
       q.product_codes AS detail
  FROM (
        SELECT product_name,
               unit_code,
               COUNT(*) AS duplicate_count,
               GROUP_CONCAT(product_code ORDER BY updated_time DESC) AS product_codes
          FROM rigour_erp.erp_product
         WHERE deleted = 0
         GROUP BY tenant_id, product_name, unit_code
        HAVING COUNT(*) > 1
         ORDER BY duplicate_count DESC
         LIMIT 20
       ) q
UNION ALL
SELECT 'erp_variant_possible_duplicate' AS audit_item,
       CONCAT('productId=', q.product_id, ',spec=', COALESCE(q.specification_snapshot, ''), ',price=', q.sale_price) AS ref_key,
       q.duplicate_count AS row_count,
       q.variant_codes AS detail
  FROM (
        SELECT product_id,
               specification_snapshot,
               sale_price,
               COUNT(*) AS duplicate_count,
               GROUP_CONCAT(variant_code ORDER BY updated_time DESC) AS variant_codes
          FROM rigour_erp.erp_product_variant
         WHERE deleted = 0
         GROUP BY tenant_id, product_id, specification_snapshot, sale_price
        HAVING COUNT(*) > 1
         ORDER BY duplicate_count DESC
         LIMIT 20
       ) q;
