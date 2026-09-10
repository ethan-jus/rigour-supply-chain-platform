-- IAM V79：补齐飞书导入中心 API 权限。
--
-- V78 只新增菜单和页面。导入中心的接口必须单独入 IAM 资源目录，
-- 否则页面可见但 /integration/feishu/import-bundles 会被权限上下文拒绝。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @feishu_import_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000380');
SET @feishu_import_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000381');
SET @feishu_write_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000382');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@feishu_import_api, @app_supply_chain, @feishu_import_page,
     'SUPPLY_CHAIN.API.INTEGRATION_FEISHU_IMPORT', 'API',
     'integration:feishu:import', '预检飞书导出数据', 10, 'ACTIVE', @changed_at, @changed_at),
    (@feishu_write_api, @app_supply_chain, @feishu_import_page,
     'SUPPLY_CHAIN.API.INTEGRATION_FEISHU_WRITE', 'API',
     'integration:feishu:write', '写入飞书导入数据', 20, 'ACTIVE', @changed_at, @changed_at)
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
    SELECT @feishu_import_api AS resource_id
    UNION ALL SELECT @feishu_write_api
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT page_grant.tenant_id, page_grant.role_id, @feishu_import_api,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource page_grant
 WHERE page_grant.resource_id = @feishu_import_page
   AND page_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @feishu_import_api AS resource_id
    UNION ALL SELECT @feishu_write_api
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
