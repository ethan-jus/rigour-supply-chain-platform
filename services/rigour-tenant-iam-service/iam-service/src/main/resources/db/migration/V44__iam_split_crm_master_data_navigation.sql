-- IAM V44：将 CRM 已落地的客户主数据能力拆分为独立导航页面。
-- 历史迁移不可改写；客户类型复用既有“客户等级与标签”页面，新增归属地区和外部员工页面。

SET @changed_at = TIMESTAMP('2026-08-13 14:30:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_assignments_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000204');
SET @crm_customer_areas = UUID_TO_BIN('019facf2-0000-7000-8000-000000000292');
SET @crm_external_staff = UUID_TO_BIN('019facf2-0000-7000-8000-000000000293');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@crm_customer_areas, @app_supply_chain, @crm_customers_menu,
     'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_AREAS', 'PAGE', NULL,
     '归属地区', 40, 'ACTIVE', @changed_at, @changed_at),
    (@crm_external_staff, @app_supply_chain, @crm_assignments_menu,
     'SUPPLY_CHAIN.PAGE.CRM_ASSIGNMENTS_EXTERNAL_STAFF', 'PAGE', NULL,
     '外部员工', 40, 'ACTIVE', @changed_at, @changed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key,
    visible, keep_alive, created_at, updated_at
) VALUES
    (@crm_customer_areas, 'supply.crm.customers.areas',
     '/supply-chain/crm/customers/areas', NULL, 1, 0, @changed_at, @changed_at),
    (@crm_external_staff, 'supply.crm.assignments.external-staff',
     '/supply-chain/crm/assignments/external-staff', NULL, 1, 0, @changed_at, @changed_at);

-- 新页面进入现行标准套餐，并授予既有租户超级管理员；普通角色仍由 IAM 按需授权。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @changed_at, NULL
FROM (
    SELECT @crm_customer_areas AS resource_id
    UNION ALL SELECT @crm_external_staff
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- V22 后租户导航使用菜单配置 INNER JOIN；补齐现有有效租户，避免新增页面被查询过滤。
INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resource_record.id,
       resource_ui.visible, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
  JOIN iam_resource_ui resource_ui
    ON resource_ui.resource_id = resource_record.id
  JOIN (
      SELECT @crm_customer_areas AS resource_id
      UNION ALL SELECT @crm_external_staff
  ) added_resources
    ON added_resources.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
   AND resource_record.status = 'ACTIVE'
   AND resource_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @crm_customer_areas AS resource_id
    UNION ALL SELECT @crm_external_staff
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
