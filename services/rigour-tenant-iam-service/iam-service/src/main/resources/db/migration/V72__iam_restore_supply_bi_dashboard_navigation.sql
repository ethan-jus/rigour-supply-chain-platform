-- IAM V72：恢复已实现的供应链 BI 看板入口，同时继续隐藏旧 BI 占位页面。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @supply_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000049');
SET @bi_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');
SET @analytics_refresh_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000361');
SET @analytics_city_cost_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000362');

-- 旧 BI 分组下有规划期占位页面，本次只恢复真实看板页面。
UPDATE iam_resource_ui resource_ui
JOIN iam_resource resource_record ON resource_record.id = resource_ui.resource_id
   SET resource_ui.visible = 0,
       resource_ui.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key <> 'supply.bi.index';

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @bi_page, @app_supply_chain, @supply_root,
    'SUPPLY_CHAIN.PAGE.BI_INDEX', 'PAGE', NULL,
    'BI 数据看板', 60, 'ACTIVE', @changed_at, @changed_at
) ON DUPLICATE KEY UPDATE
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
) VALUES (
    @bi_page, 'supply.bi.index', '/supply-chain/bi', 'TrendCharts', 1, 0, @changed_at, @changed_at
) ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, package_resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
  ) package_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @bi_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
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
   AND resource_ui.route_key <> 'supply.bi.index';

-- 已有看板查询权限的角色补齐可导航页面。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT dashboard_grant.tenant_id, dashboard_grant.role_id, @bi_page,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource dashboard_grant
 WHERE dashboard_grant.resource_id = @analytics_dashboard_read
   AND dashboard_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

-- 已有页面授权的角色补齐看板接口查询权限。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT page_grant.tenant_id, page_grant.role_id, @analytics_dashboard_read,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource page_grant
 WHERE page_grant.resource_id = @bi_page
   AND page_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_page AS resource_id
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
