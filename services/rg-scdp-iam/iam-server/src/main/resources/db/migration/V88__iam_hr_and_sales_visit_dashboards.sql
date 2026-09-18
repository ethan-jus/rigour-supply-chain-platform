-- IAM V88：HR 与销售拜访独立看板；仅复制现有 BI 读取角色的页面访问，不新增数据权限。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_hr_page = UUID_TO_BIN('692aa39e-7724-46e6-a7d2-cbb6cda405f2');
SET @bi_visit_page = UUID_TO_BIN('eac18763-ac5f-4927-b12a-234240fd7aac');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_hr_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_HR', 'PAGE', NULL, 'HR 人事看板', 42, 'ACTIVE', @changed_at, @changed_at),
    (@bi_visit_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_SALES_VISITS', 'PAGE', NULL, '销售拜访看板', 44, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_hr_page, 'supply.bi.hr',
     '/supply-chain/bi/hr', 'User', 1, 0, @changed_at, @changed_at),
    (@bi_visit_page, 'supply.bi.sales-visits',
     '/supply-chain/bi/sales-visits', 'Location', 1, 0, @changed_at, @changed_at)
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
    SELECT @bi_hr_page AS resource_id
    UNION ALL SELECT @bi_visit_page
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (SELECT @bi_hr_page AS resource_id UNION ALL SELECT @bi_visit_page) resources
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
    SELECT @bi_hr_page AS resource_id
    UNION ALL SELECT @bi_visit_page
 ) resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id = @analytics_dashboard_read
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;
