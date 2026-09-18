-- IAM V52：供应链菜单按“我方业务主流程”和“外部同步”重新分组。
--
-- 业务口径：
-- 1. ERP 商品中心只展示我方商品、分类、品牌、标签等可维护业务资料。
-- 2. 订货宝同步只作为后台来源接入，后续直接映射写入我方业务表。
-- 3. SKU、订货宝规格等旧同步档案不再注册业务菜单，避免继续以订货宝模型驱动操作流程。

SET @changed_at = TIMESTAMP('2026-08-20 16:45:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

SET @erp_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000059');
SET @erp_product_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');
SET @erp_sku_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000169');
SET @erp_category_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000170');
SET @erp_brand_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000171');
SET @erp_specification_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');
SET @erp_supplier_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000173');
SET @erp_supplier_profile_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000174');
SET @erp_supplier_product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000175');
SET @erp_supplier_price_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000176');
SET @erp_procurement_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000177');
SET @erp_procurement_request_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000178');
SET @erp_procurement_order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000179');
SET @erp_procurement_receipt_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000180');
SET @erp_procurement_return_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000181');
SET @erp_warehouse_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000182');
SET @erp_inventory_warehouse_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
SET @erp_stock_in_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
SET @erp_stock_out_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000185');
SET @erp_transfer_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000186');
SET @erp_stocktaking_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000187');
SET @erp_tag_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');
SET @erp_attributes_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000285');
SET @erp_legacy_sync_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');
SET @erp_inventory_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000188');
SET @erp_inventory_dashboard_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');
SET @erp_inventory_balance_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000190');
SET @erp_inventory_movement_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000191');
SET @erp_inventory_batch_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000192');
SET @erp_inventory_alert_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000193');
SET @erp_cost_settlement_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000194');
SET @erp_procurement_payment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000297');

SET @crm_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_customer_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000200');
SET @crm_store_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000201');
SET @crm_customer_type_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000202');
SET @crm_customer_360_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000203');
SET @crm_assignments_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000204');
SET @crm_assignment_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000205');
SET @crm_assignment_city_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000206');
SET @crm_assignment_history_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000207');
SET @crm_credit_policy_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000208');
SET @crm_customer_area_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000292');
SET @crm_external_staff_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000293');
SET @crm_shipping_address_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000294');

SET @order_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_access_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @order_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @order_stock_up_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @order_shipment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @order_return_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @order_delivery_partner_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @order_fulfillment_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @order_after_sales_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @order_center_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000112');
SET @order_index_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000113');
SET @order_all_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000114');
SET @order_pending_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000115');
SET @order_exception_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000116');
SET @order_settlement_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000235');
SET @order_settlement_collection_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000237');
SET @order_settlement_receipt_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000276');
SET @order_settlement_payment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000277');

SET @integration_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @integration_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
SET @integration_raw_data_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000085');
SET @integration_sync_tasks_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000086');
SET @integration_sync_logs_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000087');
SET @integration_connection_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000088');
SET @integration_field_mapping_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000089');
SET @integration_reconciliation_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000090');
SET @integration_sovereignty_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000091');
SET @integration_sync_batches_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000266');
SET @integration_external_mapping_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000267');
SET @integration_sync_control_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000300');
SET @settings_dictionary_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000265');

-- ERP 商品中心：只放我方可维护的商品资料。
UPDATE iam_resource
   SET display_name = '商品中心',
       sort_order = 20,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_product_menu;

UPDATE iam_resource
   SET parent_id = @erp_product_menu,
       display_name = '商品管理',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_product_page;

UPDATE iam_resource
   SET parent_id = @erp_product_menu,
       display_name = '商品分类',
       sort_order = 20,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_category_page;

UPDATE iam_resource
   SET parent_id = @erp_product_menu,
       display_name = '商品品牌',
       sort_order = 30,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_brand_page;

UPDATE iam_resource
   SET parent_id = @erp_product_menu,
       display_name = '商品标签',
       sort_order = 40,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_tag_page;

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@erp_product_menu, @erp_product_page, @erp_category_page, @erp_brand_page, @erp_tag_page);

-- 数据字典：前端和新业务表统一采用“数据字典 / 数据字典项”口径。
UPDATE iam_resource
   SET display_name = '数据字典',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @settings_dictionary_page;

-- 旧订货宝商品同步细项不再挂在 ERP 主流程菜单。
UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (@erp_sku_page, @erp_specification_page, @erp_attributes_menu, @erp_legacy_sync_page);

UPDATE iam_tenant_menu_config
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@erp_product_menu, @erp_product_page, @erp_category_page, @erp_brand_page, @erp_tag_page);

UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (@erp_sku_page, @erp_specification_page, @erp_attributes_menu, @erp_legacy_sync_page);

-- ERP 库存管理：库存调拨是独立业务菜单，不再放在入库单页面流程里混合表达。
UPDATE iam_resource
   SET display_name = '库存管理',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_inventory_menu;

-- ERP 业务主菜单：只展示新方案已经有页面承接的业务入口。
UPDATE iam_resource
   SET display_name = 'ERP',
       sort_order = 20,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_root_menu;

UPDATE iam_resource
   SET display_name = CASE id
           WHEN @erp_supplier_menu THEN '供应商'
           WHEN @erp_supplier_profile_page THEN '供应商档案'
           WHEN @erp_procurement_menu THEN '采购管理'
           WHEN @erp_procurement_order_page THEN '采购订单'
           WHEN @erp_procurement_payment_page THEN '采购付款单'
           WHEN @erp_inventory_menu THEN '库存管理'
           WHEN @erp_inventory_balance_page THEN '库存'
           WHEN @erp_stock_in_page THEN '入库单'
           WHEN @erp_stock_out_page THEN '出库单'
           WHEN @erp_transfer_page THEN '库存调拨'
           WHEN @erp_inventory_warehouse_page THEN '仓库信息'
           ELSE display_name
       END,
       sort_order = CASE id
           WHEN @erp_supplier_menu THEN 30
           WHEN @erp_supplier_profile_page THEN 10
           WHEN @erp_procurement_menu THEN 40
           WHEN @erp_procurement_order_page THEN 10
           WHEN @erp_procurement_payment_page THEN 20
           WHEN @erp_inventory_menu THEN 50
           WHEN @erp_inventory_balance_page THEN 10
           WHEN @erp_stock_in_page THEN 20
           WHEN @erp_stock_out_page THEN 30
           WHEN @erp_transfer_page THEN 40
           WHEN @erp_inventory_warehouse_page THEN 50
           ELSE sort_order
       END,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (
    @erp_supplier_menu,
    @erp_supplier_profile_page,
    @erp_procurement_menu,
    @erp_procurement_order_page,
    @erp_procurement_payment_page,
    @erp_inventory_menu,
    @erp_inventory_balance_page,
    @erp_stock_in_page,
    @erp_stock_out_page,
    @erp_transfer_page,
    @erp_inventory_warehouse_page
 );

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_root_menu,
    @erp_product_menu,
    @erp_product_page,
    @erp_category_page,
    @erp_brand_page,
    @erp_tag_page,
    @erp_supplier_menu,
    @erp_supplier_profile_page,
    @erp_procurement_menu,
    @erp_procurement_order_page,
    @erp_procurement_payment_page,
    @erp_inventory_menu,
    @erp_inventory_balance_page,
    @erp_stock_in_page,
    @erp_stock_out_page,
    @erp_transfer_page,
    @erp_inventory_warehouse_page
 );

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_supplier_product_page,
    @erp_supplier_price_page,
    @erp_procurement_request_page,
    @erp_procurement_receipt_page,
    @erp_procurement_return_page,
    @erp_warehouse_menu,
    @erp_stocktaking_page,
    @erp_inventory_dashboard_page,
    @erp_inventory_movement_page,
    @erp_inventory_batch_page,
    @erp_inventory_alert_page,
    @erp_cost_settlement_menu
 );

-- CRM 新方案：客户、商家、门店合并为“客户管理”，不再拆门店/地址/外部员工菜单。
UPDATE iam_resource
   SET display_name = 'CRM',
       sort_order = 30,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_root_menu;

UPDATE iam_resource
   SET parent_id = @crm_root_menu,
       display_name = '客户管理',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customer_page;

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@crm_root_menu, @crm_customer_page);

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @crm_customers_menu,
    @crm_store_page,
    @crm_customer_type_page,
    @crm_customer_360_page,
    @crm_assignments_menu,
    @crm_assignment_sales_page,
    @crm_assignment_city_page,
    @crm_assignment_history_page,
    @crm_credit_policy_menu,
    @crm_customer_area_page,
    @crm_external_staff_page,
    @crm_shipping_address_page
 );

-- Order 新方案：订单管理只展示销售订单，页面走 order-sales 新接口。
UPDATE iam_resource
   SET display_name = '订单管理',
       sort_order = 40,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_root_menu;

UPDATE iam_resource
   SET parent_id = @order_root_menu,
       resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_SALES_ORDERS',
       resource_type = 'PAGE',
       display_name = '销售订单',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_sales_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.order.sales-orders',
       route_path = '/supply-chain/order/sales-orders',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @order_sales_page;

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@order_root_menu, @order_sales_page);

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @order_access_menu,
    @order_stock_up_page,
    @order_shipment_page,
    @order_return_page,
    @order_delivery_partner_page,
    @order_fulfillment_menu,
    @order_after_sales_menu,
    @order_center_menu,
    @order_index_page,
    @order_all_page,
    @order_pending_page,
    @order_exception_page,
    @order_settlement_menu,
    @order_settlement_collection_menu,
    @order_settlement_receipt_page,
    @order_settlement_payment_page
 );

-- 租户菜单显隐跟随本次业务主流程白名单，避免前端页面和 IAM 菜单不一致。
INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, visible_resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @erp_root_menu AS resource_id
    UNION ALL SELECT @erp_product_menu
    UNION ALL SELECT @erp_product_page
    UNION ALL SELECT @erp_category_page
    UNION ALL SELECT @erp_brand_page
    UNION ALL SELECT @erp_tag_page
    UNION ALL SELECT @erp_supplier_menu
    UNION ALL SELECT @erp_supplier_profile_page
    UNION ALL SELECT @erp_procurement_menu
    UNION ALL SELECT @erp_procurement_order_page
    UNION ALL SELECT @erp_procurement_payment_page
    UNION ALL SELECT @erp_inventory_menu
    UNION ALL SELECT @erp_inventory_balance_page
    UNION ALL SELECT @erp_stock_in_page
    UNION ALL SELECT @erp_stock_out_page
    UNION ALL SELECT @erp_transfer_page
    UNION ALL SELECT @erp_inventory_warehouse_page
    UNION ALL SELECT @crm_root_menu
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @order_root_menu
    UNION ALL SELECT @order_sales_page
    UNION ALL SELECT @settings_dictionary_page
 ) visible_resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_sku_page,
    @erp_specification_page,
    @erp_attributes_menu,
    @erp_legacy_sync_page,
    @erp_supplier_product_page,
    @erp_supplier_price_page,
    @erp_procurement_request_page,
    @erp_procurement_receipt_page,
    @erp_procurement_return_page,
    @erp_warehouse_menu,
    @erp_stocktaking_page,
    @erp_inventory_dashboard_page,
    @erp_inventory_movement_page,
    @erp_inventory_batch_page,
    @erp_inventory_alert_page,
    @erp_cost_settlement_menu,
    @crm_customers_menu,
    @crm_store_page,
    @crm_customer_type_page,
    @crm_customer_360_page,
    @crm_assignments_menu,
    @crm_assignment_sales_page,
    @crm_assignment_city_page,
    @crm_assignment_history_page,
    @crm_credit_policy_menu,
    @crm_customer_area_page,
    @crm_external_staff_page,
    @crm_shipping_address_page,
    @order_access_menu,
    @order_stock_up_page,
    @order_shipment_page,
    @order_return_page,
    @order_delivery_partner_page,
    @order_fulfillment_menu,
    @order_after_sales_menu,
    @order_center_menu,
    @order_index_page,
    @order_all_page,
    @order_pending_page,
    @order_exception_page,
    @order_settlement_menu,
    @order_settlement_collection_menu,
    @order_settlement_receipt_page,
    @order_settlement_payment_page
 );

-- 新主流程页面授予租户超级管理员；普通角色后续仍走 IAM 精细授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, visible_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @erp_product_page AS resource_id
    UNION ALL SELECT @erp_category_page
    UNION ALL SELECT @erp_brand_page
    UNION ALL SELECT @erp_tag_page
    UNION ALL SELECT @erp_supplier_profile_page
    UNION ALL SELECT @erp_procurement_order_page
    UNION ALL SELECT @erp_procurement_payment_page
    UNION ALL SELECT @erp_inventory_balance_page
    UNION ALL SELECT @erp_stock_in_page
    UNION ALL SELECT @erp_stock_out_page
    UNION ALL SELECT @erp_transfer_page
    UNION ALL SELECT @erp_inventory_warehouse_page
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @order_sales_page
    UNION ALL SELECT @settings_dictionary_page
 ) visible_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

-- 外部同步：只保留订货宝同步中心单入口；旧分散同步子页面不再作为菜单暴露。
UPDATE iam_resource
   SET display_name = '外部同步',
       sort_order = 90,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_menu;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_menu;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @integration_sync_control_menu, @app_supply_chain, @integration_menu,
    'SUPPLY_CHAIN.MENU.INTEGRATION_SYNC_CONTROL', 'MENU', NULL,
    '订货宝同步', 10, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @integration_sync_control_menu, 'supply.integration.sync-control.menu', NULL, NULL, 1, 0, @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_resource
   SET parent_id = @integration_sync_control_menu,
       display_name = '订货宝同步中心',
       status = 'ACTIVE',
       sort_order = 10,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_overview_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.overview',
       route_path = '/supply-chain/integration',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_overview_page;

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (
    @integration_menu,
    @integration_sync_control_menu,
    @integration_overview_page
 );

UPDATE iam_resource
   SET status = 'INACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (
    @integration_raw_data_page,
    @integration_connection_page,
    @integration_sync_tasks_page,
    @integration_sync_logs_page,
    @integration_field_mapping_page,
    @integration_reconciliation_page,
    @integration_sovereignty_page,
    @integration_sync_batches_page,
    @integration_external_mapping_page
 );

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_sku_page,
    @erp_specification_page,
    @integration_raw_data_page,
    @integration_connection_page,
    @integration_sync_tasks_page,
    @integration_sync_logs_page,
    @integration_field_mapping_page,
    @integration_reconciliation_page,
    @integration_sovereignty_page,
    @integration_sync_batches_page,
    @integration_external_mapping_page
 );

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, package_resources.resource_id, @changed_at, NULL
 FROM (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @integration_overview_page
 ) package_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resource_record.id, resource_ui.visible, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_resource resource_record
    ON resource_record.id IN (
        @integration_menu,
        @integration_sync_control_menu,
        @integration_overview_page
    )
  JOIN iam_resource_ui resource_ui
    ON resource_ui.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = VALUES(visible),
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_sku_page,
    @erp_specification_page,
    @integration_raw_data_page,
    @integration_connection_page,
    @integration_sync_tasks_page,
    @integration_sync_logs_page,
    @integration_field_mapping_page,
    @integration_reconciliation_page,
    @integration_sovereignty_page,
    @integration_sync_batches_page,
    @integration_external_mapping_page
 );

DELETE FROM iam_role_resource
 WHERE resource_id IN (
    @integration_raw_data_page,
    @integration_connection_page,
    @integration_sync_tasks_page,
    @integration_sync_logs_page,
    @integration_field_mapping_page,
    @integration_reconciliation_page,
    @integration_sovereignty_page,
    @integration_sync_batches_page,
    @integration_external_mapping_page
 );

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, visible_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @integration_overview_page
 ) visible_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
