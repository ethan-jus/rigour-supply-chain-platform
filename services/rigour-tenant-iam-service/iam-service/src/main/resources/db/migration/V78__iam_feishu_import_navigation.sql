-- IAM V78：新增飞书导入中心入口。
--
-- 飞书导入作为 Integration 统一入口，不拆到 CRM/ERP/Order/IAM 各业务页面。
-- 订货宝同步中心仍保留在同一个“外部同步 / 同步控制”分组下。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @integration_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @integration_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
SET @integration_sync_control_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000300');
SET @feishu_import_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000380');

UPDATE iam_resource
   SET display_name = '外部同步',
       sort_order = 90,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_menu;

UPDATE iam_resource
   SET parent_id = @integration_menu,
       display_name = '同步控制',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_sync_control_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.sync-control.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_sync_control_menu;

UPDATE iam_resource
   SET parent_id = @integration_sync_control_menu,
       display_name = '订货宝同步中心',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_overview_page
   AND application_id = @app_supply_chain;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @feishu_import_page, @app_supply_chain, @integration_sync_control_menu,
    'SUPPLY_CHAIN.PAGE.INTEGRATION_FEISHU_IMPORT', 'PAGE', NULL,
    '飞书导入中心', 20, 'ACTIVE', @changed_at, @changed_at
)
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
) VALUES (
    @feishu_import_page, 'supply.integration.feishu-import',
    '/supply-chain/integration/feishu-import', 'Connection', 1, 0, @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @integration_overview_page
    UNION ALL SELECT @feishu_import_page
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @integration_overview_page
    UNION ALL SELECT @feishu_import_page
 ) resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    version = iam_tenant_menu_config.version + 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @feishu_import_page
 ) resources
 WHERE existing_grant.resource_id = @integration_overview_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @feishu_import_page
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
