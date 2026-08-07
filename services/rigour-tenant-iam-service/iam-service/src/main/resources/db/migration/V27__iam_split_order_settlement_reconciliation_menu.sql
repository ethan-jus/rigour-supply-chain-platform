-- IAM V27：将订单结算管理下的“财务对账”和“对账差异”拆分为两个菜单入口。
-- 本迁移只维护菜单资源、路由和既有租户授权，不创建财务对账业务表，也不接入财务系统。

SET @changed_at = TIMESTAMP('2026-08-07 18:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @order_settlement = UUID_TO_BIN('019facf2-0000-7000-8000-000000000235');
SET @order_settlement_differences = UUID_TO_BIN('019facf2-0000-7000-8000-000000000275');

-- 原“对账差异”路由保留，调整为财务人工对账入口；收付记录顺延到第三项。
UPDATE iam_resource
   SET display_name = CASE resource_code
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION' THEN '财务对账'
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS' THEN '收付记录'
           ELSE display_name
       END,
       sort_order = CASE resource_code
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION' THEN 20
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS' THEN 30
           ELSE sort_order
       END,
       version = version + 1,
       updated_at = @changed_at
 WHERE application_id = @app_supply_chain
   AND resource_code IN (
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION',
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS'
   );

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @order_settlement_differences,
    @app_supply_chain,
    @order_settlement,
    'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_DIFFERENCES',
    'PAGE',
    NULL,
    '对账差异',
    40,
    'ACTIVE',
    @changed_at,
    @changed_at
);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @order_settlement_differences,
    'supply.order.settlement.differences',
    '/supply-chain/order/settlement/differences',
    NULL,
    1,
    0,
    @changed_at,
    @changed_at
);

-- 新页面进入标准套餐，并授予既有租户超级管理员访问权。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'),
       @order_settlement_differences,
       @changed_at,
       NULL
 WHERE NOT EXISTS (
     SELECT 1
       FROM iam_package_resource
      WHERE package_version_id = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
        AND resource_id = @order_settlement_differences
 );

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id,
       role_record.id,
       @order_settlement_differences,
       'ACTIVE',
       @changed_at,
       @changed_at
  FROM iam_role role_record
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = @order_settlement_differences
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

-- 新页面默认在当前有效租户中可见；不覆盖租户已有的展示名、图标或排序覆盖。
INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id,
       @order_settlement_differences,
       1,
       @changed_at,
       @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = @order_settlement_differences
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE visible = 1, updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
