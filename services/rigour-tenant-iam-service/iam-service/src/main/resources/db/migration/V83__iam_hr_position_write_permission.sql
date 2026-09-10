-- IAM V83：补齐 HR 岗位职位维护权限。
--
-- V82 已在共享 DEV 执行；后续新增权限必须独立迁移，避免修改已执行迁移导致 checksum 不一致。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @hr_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @hr_index_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000062');
SET @hr_position_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000385');
SET @hr_position_write_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000388');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_position_write_api, @app_supply_chain, @hr_position_page,
    'SUPPLY_CHAIN.API.HR_POSITION_WRITE', 'API',
    'hr:position:write', '维护HR岗位职位', 20, 'ACTIVE', @changed_at, @changed_at
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
VALUES (@standard_package_version, @hr_position_write_api, @changed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @hr_position_write_api,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id IN (@hr_menu, @hr_index_page, @hr_position_page)
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, @hr_position_write_api,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
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
