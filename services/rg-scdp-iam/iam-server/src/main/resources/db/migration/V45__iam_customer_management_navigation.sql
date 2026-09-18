-- IAM V45：将 CRM 客户主数据整理为客户管理下的四个同级页面。

SET @changed_at = TIMESTAMP('2026-08-13 15:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_shipping_addresses = UUID_TO_BIN('019facf2-0000-7000-8000-000000000294');

UPDATE iam_resource
   SET display_name='客户管理', version=version+1, updated_at=@changed_at
 WHERE id=@crm_customers_menu;

UPDATE iam_resource
   SET display_name='客户档案', version=version+1, updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000200');

UPDATE iam_resource
   SET display_name='客户类型', version=version+1, updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000202');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @crm_shipping_addresses, @app_supply_chain, @crm_customers_menu,
    'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_SHIPPING_ADDRESSES', 'PAGE', NULL,
    '收货地址簿', 20, 'ACTIVE', @changed_at, @changed_at
);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key,
    visible, keep_alive, created_at, updated_at
) VALUES (
    @crm_shipping_addresses, 'supply.crm.customers.shipping-addresses',
    '/supply-chain/crm/customers/shipping-addresses', NULL,
    1, 0, @changed_at, @changed_at
);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), @crm_shipping_addresses, @changed_at, NULL
ON DUPLICATE KEY UPDATE created_at=created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @crm_shipping_addresses, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE updated_at=@changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, @crm_shipping_addresses,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 WHERE role_record.role_code='TENANT_SUPER_ADMIN'
   AND role_record.role_type='SYSTEM'
   AND role_record.status='ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@changed_at;

UPDATE iam_tenant
   SET policy_version=policy_version+1, version=version+1, updated_at=@changed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;
