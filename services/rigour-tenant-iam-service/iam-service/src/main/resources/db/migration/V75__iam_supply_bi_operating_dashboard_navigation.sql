-- IAM V75：按经营总览、销售管理、活动样例、商品库存运营重排供应链 BI 菜单。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @bi_sales_collection_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000370');
SET @bi_product_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000371');
SET @bi_city_cost_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000372');
SET @bi_inventory_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000373');
SET @bi_gross_profit_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000374');
SET @bi_payment_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000375');
SET @bi_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000376');
SET @bi_activity_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000377');
SET @bi_product_inventory_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000378');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_sales_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_SALES', 'PAGE', NULL, '销售看板', 20, 'ACTIVE', @changed_at, @changed_at),
    (@bi_activity_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_ACTIVITY', 'PAGE', NULL, '活动看板', 30, 'ACTIVE', @changed_at, @changed_at),
    (@bi_product_inventory_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_PRODUCT_INVENTORY', 'PAGE', NULL, '商品/库存看板', 40, 'ACTIVE', @changed_at, @changed_at)
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

UPDATE iam_resource
   SET display_name = CASE id
       WHEN @bi_overview_page THEN '供应链经营总览'
       WHEN @bi_gross_profit_page THEN '销售毛利分析'
       WHEN @bi_city_cost_page THEN '城市成本看板'
       ELSE display_name
   END,
       sort_order = CASE id
       WHEN @bi_overview_page THEN 10
       WHEN @bi_gross_profit_page THEN 50
       WHEN @bi_city_cost_page THEN 60
       ELSE sort_order
   END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@bi_overview_page, @bi_gross_profit_page, @bi_city_cost_page);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_sales_page, 'supply.bi.sales', '/supply-chain/bi/sales', 'DataAnalysis', 1, 0, @changed_at, @changed_at),
    (@bi_activity_page, 'supply.bi.activity', '/supply-chain/bi/activity', 'Tickets', 1, 0, @changed_at, @changed_at),
    (@bi_product_inventory_page, 'supply.bi.product-inventory', '/supply-chain/bi/product-inventory', 'Goods', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    updated_at = @changed_at;

UPDATE iam_resource_ui resource_ui
JOIN iam_resource resource_record ON resource_record.id = resource_ui.resource_id
   SET resource_ui.visible = CASE
       WHEN resource_ui.route_key IN (
           'supply.bi.menu',
           'supply.bi.index',
           'supply.bi.sales',
           'supply.bi.activity',
           'supply.bi.product-inventory',
           'supply.bi.gross-profit',
           'supply.bi.city-cost'
       ) THEN 1 ELSE 0 END,
       resource_ui.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_ui.route_key LIKE 'supply.bi.%';

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_sales_page AS resource_id
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @analytics_dashboard_read
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @bi_sales_page AS resource_id
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_inventory_page
 ) resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = menu_config.resource_id
   SET menu_config.visible = CASE
       WHEN resource_ui.route_key IN (
           'supply.bi.menu',
           'supply.bi.index',
           'supply.bi.sales',
           'supply.bi.activity',
           'supply.bi.product-inventory',
           'supply.bi.gross-profit',
           'supply.bi.city-cost'
       ) THEN 1 ELSE 0 END,
       menu_config.updated_at = @changed_at
 WHERE resource_ui.route_key LIKE 'supply.bi.%';

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_sales_page AS resource_id
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
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
    SELECT @bi_sales_page AS resource_id
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @analytics_dashboard_read
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
