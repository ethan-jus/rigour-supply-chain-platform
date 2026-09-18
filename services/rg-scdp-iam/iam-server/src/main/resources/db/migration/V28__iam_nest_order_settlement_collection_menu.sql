-- IAM V28：将“收付记录”调整为可展开菜单，并增加收款单、付款单页面。
-- 只调整导航资源和授权，不新增财务业务能力；收付记录作为菜单分组，不再作为可点击页面。

SET @changed_at = TIMESTAMP('2026-08-07 18:20:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @order_settlement = UUID_TO_BIN('019facf2-0000-7000-8000-000000000235');
SET @order_settlement_collections = UUID_TO_BIN('019facf2-0000-7000-8000-000000000237');
SET @order_settlement_receipts = UUID_TO_BIN('019facf2-0000-7000-8000-000000000276');
SET @order_settlement_payments = UUID_TO_BIN('019facf2-0000-7000-8000-000000000277');

-- 保留原资源编码，将“收付记录”从页面调整为无路由的可展开菜单。
UPDATE iam_resource
   SET resource_type = 'MENU',
       display_name = '收付记录',
       sort_order = 30,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_settlement_collections
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.order.settlement.collections',
       route_path = NULL,
       updated_at = @changed_at
 WHERE resource_id = @order_settlement_collections;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@order_settlement_receipts, @app_supply_chain, @order_settlement_collections,
     'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIPTS', 'PAGE', NULL, '收款单', 10, 'ACTIVE', @changed_at, @changed_at),
    (@order_settlement_payments, @app_supply_chain, @order_settlement_collections,
     'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_PAYMENTS', 'PAGE', NULL, '付款单', 20, 'ACTIVE', @changed_at, @changed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@order_settlement_receipts, 'supply.order.settlement.receipts',
     '/supply-chain/order/settlement/receipts', NULL, 1, 0, @changed_at, @changed_at),
    (@order_settlement_payments, 'supply.order.settlement.payments',
     '/supply-chain/order/settlement/payments', NULL, 1, 0, @changed_at, @changed_at);

-- 新页面进入标准套餐。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), added_resource.resource_id, @changed_at, NULL
  FROM (
      SELECT @order_settlement_receipts AS resource_id
      UNION ALL SELECT @order_settlement_payments
  ) added_resource
 WHERE NOT EXISTS (
     SELECT 1
       FROM iam_package_resource package_resource
      WHERE package_resource.package_version_id = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
        AND package_resource.resource_id = added_resource.resource_id
 );

-- 既有租户超级管理员继续获得新页面访问权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resource.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN (
      SELECT @order_settlement_receipts AS resource_id
      UNION ALL SELECT @order_settlement_payments
  ) added_resource ON added_resource.resource_id = package_resource.resource_id
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, added_resource.resource_id,
       1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN (
      SELECT @order_settlement_receipts AS resource_id
      UNION ALL SELECT @order_settlement_payments
  ) added_resource ON added_resource.resource_id = package_resource.resource_id
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
