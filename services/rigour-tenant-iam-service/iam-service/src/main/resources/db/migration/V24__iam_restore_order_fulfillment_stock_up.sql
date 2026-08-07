-- IAM V24：将既有“出库/发货”资源重新挂回订单管理 → 履约编排。
-- 不新增资源、不改路由路径，复用V12/V19的稳定资源ID和权限关系。

SET @changed_at = TIMESTAMP('2026-08-07 16:00:00.000000');
SET @orders = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_fulfillment = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @order_stock_up = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');

UPDATE iam_resource
   SET parent_id = @order_fulfillment,
       resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_STOCK_UP',
       resource_type = 'PAGE',
       display_name = '出库/发货',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_stock_up
   AND application_id = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003')
   AND parent_id = @orders;

UPDATE iam_resource_ui
   SET route_key = 'supply.order.stock-up',
       route_path = '/supply-chain/order/stock-up',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @order_stock_up;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
