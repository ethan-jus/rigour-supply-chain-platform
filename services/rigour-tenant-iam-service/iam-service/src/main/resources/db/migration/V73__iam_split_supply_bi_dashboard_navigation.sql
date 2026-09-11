-- IAM V73：将供应链 BI 单页拆成业务可识别的数据看板菜单。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @supply_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000049');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @bi_sales_collection_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000370');
SET @bi_product_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000371');
SET @bi_city_cost_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000372');
SET @bi_inventory_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000373');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');
SET @analytics_refresh_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000361');
SET @analytics_city_cost_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000362');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_menu, @app_supply_chain, @supply_root,
     'SUPPLY_CHAIN.MENU.BI', 'MENU', NULL, '数据看板', 60, 'ACTIVE', @changed_at, @changed_at),
    (@bi_overview_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_OVERVIEW', 'PAGE', NULL, '供应链经营总览', 10, 'ACTIVE', @changed_at, @changed_at),
    (@bi_sales_collection_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_SALES_COLLECTION', 'PAGE', NULL, '销售与回款看板', 20, 'ACTIVE', @changed_at, @changed_at),
    (@bi_product_sales_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_PRODUCT_SALES', 'PAGE', NULL, '商品销售统计', 30, 'ACTIVE', @changed_at, @changed_at),
    (@bi_city_cost_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_CITY_COST', 'PAGE', NULL, '城市成本看板', 40, 'ACTIVE', @changed_at, @changed_at),
    (@bi_inventory_risk_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_INVENTORY_RISK', 'PAGE', NULL, '库存风险看板', 50, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_menu, 'supply.bi.menu', NULL, 'TrendCharts', 1, 0, @changed_at, @changed_at),
    (@bi_overview_page, 'supply.bi.index', '/supply-chain/bi', 'DataLine', 1, 0, @changed_at, @changed_at),
    (@bi_sales_collection_page, 'supply.bi.sales-collection', '/supply-chain/bi/sales-collection', 'Money', 1, 0, @changed_at, @changed_at),
    (@bi_product_sales_page, 'supply.bi.product-sales', '/supply-chain/bi/product-sales', 'Goods', 1, 0, @changed_at, @changed_at),
    (@bi_city_cost_page, 'supply.bi.city-cost', '/supply-chain/bi/city-cost', 'Coin', 1, 0, @changed_at, @changed_at),
    (@bi_inventory_risk_page, 'supply.bi.inventory-risk', '/supply-chain/bi/inventory-risk', 'Warning', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    updated_at = @changed_at;

UPDATE iam_resource_ui resource_ui
JOIN iam_resource resource_record ON resource_record.id = resource_ui.resource_id
   SET resource_ui.visible = 0,
       resource_ui.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key NOT IN (
       'supply.bi.menu',
       'supply.bi.index',
       'supply.bi.sales-collection',
       'supply.bi.product-sales',
       'supply.bi.city-cost',
       'supply.bi.inventory-risk'
   );

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, package_resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_collection_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
  ) package_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, visible_resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_collection_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
 ) visible_resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = menu_config.resource_id
   SET menu_config.visible = 0,
       menu_config.updated_at = @changed_at
 WHERE resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key NOT IN (
       'supply.bi.menu',
       'supply.bi.index',
       'supply.bi.sales-collection',
       'supply.bi.product-sales',
       'supply.bi.city-cost',
       'supply.bi.inventory-risk'
   );

-- 已有看板查询权限或旧 BI 页面权限的角色，补齐新的业务看板页面。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, visible_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_collection_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
 ) visible_resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id IN (@bi_overview_page, @analytics_dashboard_read)
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_collection_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;
