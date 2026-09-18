-- IAM V48：供应链业务字典页面和公共业务设置服务权限。

SET @changed_at = TIMESTAMP('2026-08-15 10:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @dict_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000265');
SET @dict_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000295');
SET @dict_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000296');

UPDATE iam_resource
   SET display_name='业务字典', status='ACTIVE', updated_at=@changed_at
 WHERE id=@dict_page;

UPDATE iam_resource_ui
   SET visible=1, updated_at=@changed_at
 WHERE resource_id=@dict_page;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@dict_read, @app_supply_chain, @dict_page,
     'SUPPLY_CHAIN.API.BUSINESS_DICTIONARY_READ', 'API',
     'business-settings:dict:read', '查询业务字典', 10, 'ACTIVE', @changed_at, @changed_at),
    (@dict_write, @app_supply_chain, @dict_page,
     'SUPPLY_CHAIN.API.BUSINESS_DICTIONARY_WRITE', 'API',
     'business-settings:dict:write', '维护业务字典', 20, 'ACTIVE', @changed_at, @changed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @changed_at, NULL
FROM (SELECT @dict_read AS resource_id UNION ALL SELECT @dict_write) resources
ON DUPLICATE KEY UPDATE created_at=created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (SELECT @dict_read AS resource_id UNION ALL SELECT @dict_write) resources
 WHERE role_record.role_code='TENANT_SUPER_ADMIN'
   AND role_record.role_type='SYSTEM'
   AND role_record.status='ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@changed_at;

UPDATE iam_tenant_menu_config
   SET visible=1, updated_at=@changed_at
 WHERE resource_id=@dict_page;

UPDATE iam_tenant
   SET policy_version=policy_version+1,
       version=version+1,
       updated_at=@changed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;
