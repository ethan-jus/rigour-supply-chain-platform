-- IAM V70：供应链 BI 看板独立权限。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');
SET @analytics_refresh_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000361');
SET @analytics_city_cost_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000362');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@analytics_dashboard_read, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.ANALYTICS_SUPPLY_DASHBOARD_READ', 'API',
     'analytics:dashboard:read', '查询供应链BI看板', 10, 'ACTIVE', @changed_at, @changed_at),
    (@analytics_refresh_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.ANALYTICS_SUPPLY_REFRESH_WRITE', 'API',
     'analytics:refresh:write', '刷新供应链BI数据', 20, 'ACTIVE', @changed_at, @changed_at),
    (@analytics_city_cost_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.ANALYTICS_CITY_COST_WRITE', 'API',
     'analytics:city-cost:write', '导入城市端成本', 30, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resource_id, @changed_at, NULL
  FROM (
    SELECT @analytics_dashboard_read AS resource_id
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @analytics_dashboard_read,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @bi_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @analytics_dashboard_read AS resource_id
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

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
