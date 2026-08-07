-- IAM V25：补齐既有租户的“出库/发货”菜单配置。
-- V24恢复了平台资源，但V22已经把该资源复制为租户默认 visible=0；导航接口仍会因此过滤掉菜单。

SET @changed_at = TIMESTAMP('2026-08-07 16:40:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @order_stock_up = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');

-- 兼容V24尚未在目标环境执行的情况，确保平台资源和前端路由仍然一致。
UPDATE iam_resource
   SET parent_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106'),
       resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_STOCK_UP',
       resource_type = 'PAGE',
       display_name = '出库/发货',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_stock_up
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.order.stock-up',
       route_path = '/supply-chain/order/stock-up',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @order_stock_up;

-- 确保标准套餐仍包含该稳定资源，避免导航查询因套餐关联缺失而过滤。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), @order_stock_up, @changed_at, NULL
 WHERE NOT EXISTS (
     SELECT 1 FROM iam_package_resource
      WHERE package_version_id = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
        AND resource_id = @order_stock_up
 );

-- 既有租户超级管理员继续保有该页面资源授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, @order_stock_up, 'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = @order_stock_up
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

-- 导航接口要求租户配置可见；只处理当前有效套餐，不影响已被租户主动关闭的其他菜单。
INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @order_stock_up, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = @order_stock_up
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
