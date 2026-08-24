-- IAM V50：在“采购管理”下增加“采购付款单”菜单及稳定路由占位。
-- 当前不接入付款业务接口；仅向已有采购页面权限的角色追加该页面资源。

SET @changed_at = TIMESTAMP('2026-08-17 16:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @procurement_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000177');
SET @procurement_payment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000297');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @procurement_payment_page, @app_supply_chain, @procurement_menu,
    'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_PAYMENTS', 'PAGE', NULL,
    '采购付款单', 50, 'ACTIVE', @changed_at, @changed_at
);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key,
    visible, keep_alive, created_at, updated_at
) VALUES (
    @procurement_payment_page, 'supply.erp.procurement.payments',
    '/supply-chain/erp/procurement/payments', NULL,
    1, 0, @changed_at, @changed_at
);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'),
       @procurement_payment_page, @changed_at, NULL
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @procurement_payment_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE updated_at = @changed_at;

-- 沿用既有采购菜单的业务角色边界，不将占位页扩大到无采购权限的角色。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT role_record.tenant_id, role_record.id, @procurement_payment_page,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
  JOIN iam_role_resource existing_grant
    ON existing_grant.tenant_id = role_record.tenant_id
   AND existing_grant.role_id = role_record.id
   AND existing_grant.status = 'ACTIVE'
 WHERE role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
   AND existing_grant.resource_id IN (
       UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),
       UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),
       UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),
       UUID_TO_BIN('019facf2-0000-7000-8000-000000000181')
   )
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource payment_grant
        WHERE payment_grant.tenant_id = tenant_record.id
          AND payment_grant.resource_id = @procurement_payment_page
          AND payment_grant.status = 'ACTIVE'
   );
