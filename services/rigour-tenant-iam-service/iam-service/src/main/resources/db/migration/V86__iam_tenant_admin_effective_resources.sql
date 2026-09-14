-- Restore the active first-party order write contract removed with the obsolete DHB sync UI in V16.
-- Restore the standard package contract without silently widening custom package entitlements.
SET @changed_at = UTC_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @sales_order_page = (SELECT id FROM iam_resource WHERE resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_SALES_ORDERS');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @order_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000120');

INSERT INTO iam_resource
    (id, application_id, parent_id, resource_code, resource_type, permission_code,
     display_name, sort_order, status, created_at, updated_at)
VALUES (@order_write, @app_supply_chain, @sales_order_page, 'SUPPLY_CHAIN.API.ORDER_WRITE',
        'API', 'order:write', '维护销售订单、发货与收退款', 20, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE parent_id = VALUES(parent_id), display_name = VALUES(display_name),
    updated_at = @changed_at;

UPDATE iam_resource SET parent_id = @sales_order_page, updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.ORDER_READ' AND parent_id IS NULL;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT entitled.package_version_id, resource_record.id, @changed_at, NULL
  FROM iam_package_resource entitled
  JOIN iam_resource resource_record ON resource_record.resource_code = 'SUPPLY_CHAIN.API.ORDER_WRITE'
 WHERE entitled.resource_id = @sales_order_page
   AND entitled.package_version_id = @standard_package_version
ON DUPLICATE KEY UPDATE created_at = iam_package_resource.created_at;

-- V36 incorrectly attached ERP supply permissions to a hidden sales-publishing API.
SET @erp_menu = (SELECT id FROM iam_resource WHERE resource_code = 'SUPPLY_CHAIN.MENU.ERP');
UPDATE iam_resource SET parent_id = @erp_menu, updated_at = @changed_at, version = version + 1
 WHERE resource_code IN ('SUPPLY_CHAIN.API.ERP_SUPPLY_READ', 'SUPPLY_CHAIN.API.ERP_SUPPLY_WRITE')
   AND @erp_menu IS NOT NULL AND (parent_id IS NULL OR parent_id <> @erp_menu);

-- Tenant administrator permissions are computed from live entitled resources, never a wildcard.
-- Existing custom/system roles retain explicit grants. No user or role assignments are inserted.
-- BEGIN EFFECTIVE ROLE VIEW
CREATE VIEW iam_effective_tenant_role_resource AS
SELECT DISTINCT role_record.tenant_id,
       role_record.id AS role_id,
       resource_record.id AS resource_id,
       'ACTIVE' AS status
  FROM iam_role role_record
  JOIN iam_tenant tenant_record
    ON tenant_record.id = role_record.tenant_id
   AND tenant_record.status = 'ACTIVE' AND tenant_record.deleted_at IS NULL
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.deleted_at IS NULL
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
   AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
  JOIN iam_application application_record
    ON application_record.id = resource_record.application_id
   AND application_record.app_scope = 'TENANT'
   AND application_record.status = 'ACTIVE' AND application_record.deleted_at IS NULL
  LEFT JOIN iam_role_resource explicit_grant
    ON explicit_grant.tenant_id = role_record.tenant_id
   AND explicit_grant.role_id = role_record.id
   AND explicit_grant.resource_id = resource_record.id
   AND explicit_grant.status = 'ACTIVE'
 WHERE role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
   AND ((role_record.role_type = 'SYSTEM' AND role_record.role_code = 'TENANT_SUPER_ADMIN')
        OR explicit_grant.resource_id IS NOT NULL);
-- END EFFECTIVE ROLE VIEW

-- BEGIN ADMIN GRANT BACKFILL
-- Persist the current administrator baseline without touching ordinary roles or user assignments.
INSERT INTO iam_role_resource
    (tenant_id, role_id, resource_id, status, created_at, created_by, updated_at, updated_by)
SELECT DISTINCT role_record.tenant_id, role_record.id, resource_record.id,
       'ACTIVE', UTC_TIMESTAMP(6), NULL, UTC_TIMESTAMP(6), NULL
  FROM iam_role role_record
  JOIN iam_tenant tenant_record ON tenant_record.id = role_record.tenant_id
   AND tenant_record.status = 'ACTIVE' AND tenant_record.deleted_at IS NULL
  JOIN iam_tenant_subscription subscription ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.deleted_at IS NULL
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record ON resource_record.id = package_resource.resource_id
   AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
  JOIN iam_application application_record ON application_record.id = resource_record.application_id
   AND application_record.app_scope = 'TENANT'
   AND application_record.status = 'ACTIVE' AND application_record.deleted_at IS NULL
 WHERE role_record.role_type = 'SYSTEM' AND role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = UTC_TIMESTAMP(6);
-- END ADMIN GRANT BACKFILL

-- BEGIN MISSING MENU CONFIG
-- Preserve every existing tenant visibility/label/group override, including explicit hidden menus.
INSERT INTO iam_tenant_menu_config
    (tenant_id, resource_id, visible, version, created_at, created_by, updated_at, updated_by)
SELECT DISTINCT tenant_record.id, resource_record.id, resource_ui.visible, 0,
       UTC_TIMESTAMP(6), NULL, UTC_TIMESTAMP(6), NULL
  FROM iam_tenant tenant_record
  JOIN iam_tenant_subscription subscription ON subscription.tenant_id = tenant_record.id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED') AND subscription.deleted_at IS NULL
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record ON resource_record.id = package_resource.resource_id
   AND resource_record.resource_type IN ('MENU', 'PAGE')
   AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
  JOIN iam_application application_record ON application_record.id = resource_record.application_id
   AND application_record.app_scope = 'TENANT'
   AND application_record.status = 'ACTIVE' AND application_record.deleted_at IS NULL
  JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = resource_record.id
 WHERE tenant_record.status = 'ACTIVE' AND tenant_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE resource_id = iam_tenant_menu_config.resource_id;
-- END MISSING MENU CONFIG
