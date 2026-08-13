-- IAM V42：CRM 客户本地查询与订货宝同步权限。

SET @seed_at = TIMESTAMP('2026-08-12 16:30:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_customer_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000290');
SET @crm_customer_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000291');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@crm_customer_read, @app_supply_chain, @crm_customers_menu,
     'SUPPLY_CHAIN.API.CRM_CUSTOMER_READ', 'API', 'crm:customer:read',
     '查询CRM客户主数据', 10, 'ACTIVE', @seed_at, @seed_at),
    (@crm_customer_write, @app_supply_chain, @crm_customers_menu,
     'SUPPLY_CHAIN.API.CRM_CUSTOMER_WRITE', 'API', 'crm:customer:write',
     '同步订货宝CRM客户主数据', 20, 'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @crm_customer_read AS resource_id
    UNION ALL SELECT @crm_customer_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @crm_customer_read AS resource_id
    UNION ALL SELECT @crm_customer_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;
