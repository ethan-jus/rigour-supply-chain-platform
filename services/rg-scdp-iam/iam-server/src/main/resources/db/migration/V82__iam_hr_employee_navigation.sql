-- IAM V82：开放 HR 员工主档导航和读写权限。
--
-- 人员主档归 HR 管理；IAM 只负责登录账号、角色和权限。这里仅补菜单和 API 权限目录。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @hr_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @hr_index_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000062');
SET @hr_employee_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000383');
SET @hr_employee_read_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000384');
SET @hr_employee_sync_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000387');
SET @hr_position_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000385');
SET @hr_position_read_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000386');

UPDATE iam_resource
   SET display_name = '人事与绩效',
       sort_order = 75,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @hr_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.hr.menu',
       route_path = NULL,
       icon_key = 'Avatar',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @hr_menu;

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @hr_index_page;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_employee_page, @app_supply_chain, @hr_menu,
    'SUPPLY_CHAIN.PAGE.HR_EMPLOYEE', 'PAGE', NULL,
    '员工主档', 10, 'ACTIVE', @changed_at, @changed_at
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
    @hr_employee_page, 'supply.hr.employees',
    '/supply-chain/hr/employees', 'UserFilled', 1, 0, @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_employee_sync_api, @app_supply_chain, @hr_employee_page,
    'SUPPLY_CHAIN.API.HR_EMPLOYEE_SYNC', 'API',
    'hr:employee:sync', '同步HR员工主档', 20, 'ACTIVE', @changed_at, @changed_at
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

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_employee_read_api, @app_supply_chain, @hr_employee_page,
    'SUPPLY_CHAIN.API.HR_EMPLOYEE_READ', 'API',
    'hr:employee:read', '读取HR员工主档', 10, 'ACTIVE', @changed_at, @changed_at
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

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_position_page, @app_supply_chain, @hr_menu,
    'SUPPLY_CHAIN.PAGE.HR_POSITION', 'PAGE', NULL,
    '岗位职位', 20, 'ACTIVE', @changed_at, @changed_at
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
    @hr_position_page, 'supply.hr.positions',
    '/supply-chain/hr/positions', 'PriceTag', 1, 0, @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_position_read_api, @app_supply_chain, @hr_position_page,
    'SUPPLY_CHAIN.API.HR_POSITION_READ', 'API',
    'hr:position:read', '读取HR岗位职位', 10, 'ACTIVE', @changed_at, @changed_at
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

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @hr_menu AS resource_id
    UNION ALL SELECT @hr_employee_page
    UNION ALL SELECT @hr_employee_read_api
    UNION ALL SELECT @hr_employee_sync_api
    UNION ALL SELECT @hr_position_page
    UNION ALL SELECT @hr_position_read_api
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @hr_menu AS resource_id
    UNION ALL SELECT @hr_employee_page
    UNION ALL SELECT @hr_position_page
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
    SELECT @hr_menu AS resource_id
    UNION ALL SELECT @hr_employee_page
    UNION ALL SELECT @hr_employee_read_api
    UNION ALL SELECT @hr_employee_sync_api
    UNION ALL SELECT @hr_position_page
    UNION ALL SELECT @hr_position_read_api
 ) resources
 WHERE existing_grant.resource_id IN (@hr_menu, @hr_index_page)
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @hr_menu AS resource_id
    UNION ALL SELECT @hr_employee_page
    UNION ALL SELECT @hr_employee_read_api
    UNION ALL SELECT @hr_employee_sync_api
    UNION ALL SELECT @hr_position_page
    UNION ALL SELECT @hr_position_read_api
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
