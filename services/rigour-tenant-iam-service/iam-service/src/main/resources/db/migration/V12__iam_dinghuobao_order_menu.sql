-- IAM V12：将供应链“订单”入口收敛为订货宝，并补齐订货宝一期只读导航。
-- 订单同步仍属于供应链内部应用；本迁移只增加菜单/页面/API资源，不提供订货宝写事实操作。

SET @seed_at = TIMESTAMP('2026-08-03 00:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @order_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');

UPDATE iam_resource
   SET display_name = '订货宝', version = version + 1, updated_at = @seed_at
 WHERE id = @order_menu AND resource_code = 'SUPPLY_CHAIN.MENU.ORDER';

UPDATE iam_resource
   SET display_name = '订单', version = version + 1, updated_at = @seed_at
 WHERE id = @order_page AND resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_INDEX';

UPDATE iam_resource_ui
   SET icon_key = 'ShoppingCart', updated_at = @seed_at
 WHERE resource_id = @order_menu;

SET @r101 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @r102 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @r103 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @r104 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @r105 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @r106 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @r107 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000107');
SET @r108 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000108');
SET @r109 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000109');
SET @r110 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000110');
SET @r111 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @r112 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000112');
SET @r113 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000113');
SET @r114 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000114');
SET @r115 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000115');
SET @r116 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000116');
SET @r117 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000117');
SET @r118 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000118');
SET @r119 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000119');
SET @r120 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000120');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r101, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_LIST', 'PAGE', NULL, '订货单', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r102, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STOCK_UP', 'PAGE', NULL, '出库发货', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r103, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_SHIPMENTS', 'PAGE', NULL, '发货单', 40, 'ACTIVE', @seed_at, @seed_at),
    (@r104, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_RETURNS', 'PAGE', NULL, '退货单', 50, 'ACTIVE', @seed_at, @seed_at),
    (@r105, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_DELIVERY_PARTNERS', 'PAGE', NULL, '配送伙伴', 60, 'ACTIVE', @seed_at, @seed_at),
    (@r106, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_GOODS', 'PAGE', NULL, '订单商品统计', 70, 'ACTIVE', @seed_at, @seed_at),
    (@r107, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_PENDING_STOCK', 'PAGE', NULL, '待出库统计', 80, 'ACTIVE', @seed_at, @seed_at),
    (@r108, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_SHIPPED', 'PAGE', NULL, '已出库统计', 90, 'ACTIVE', @seed_at, @seed_at),
    (@r109, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_PENDING_DELIVERY', 'PAGE', NULL, '待发货统计', 100, 'ACTIVE', @seed_at, @seed_at),
    (@r110, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_RETURNS', 'PAGE', NULL, '退单商品统计', 110, 'ACTIVE', @seed_at, @seed_at),
    (@r111, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_GOODS', 'PAGE', NULL, '商品', 120, 'ACTIVE', @seed_at, @seed_at),
    (@r112, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_MARKETING', 'PAGE', NULL, '营销', 130, 'ACTIVE', @seed_at, @seed_at),
    (@r113, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_INVENTORY', 'PAGE', NULL, '库存', 140, 'ACTIVE', @seed_at, @seed_at),
    (@r114, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_CUSTOMER', 'PAGE', NULL, '客户', 150, 'ACTIVE', @seed_at, @seed_at),
    (@r115, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_FINANCE', 'PAGE', NULL, '资金', 160, 'ACTIVE', @seed_at, @seed_at),
    (@r116, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_REPORT', 'PAGE', NULL, '报表', 170, 'ACTIVE', @seed_at, @seed_at),
    (@r117, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_DATA', 'PAGE', NULL, '数据', 180, 'ACTIVE', @seed_at, @seed_at),
    (@r118, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_SYSTEM', 'PAGE', NULL, '系统', 190, 'ACTIVE', @seed_at, @seed_at),
    (@r119, @app_supply_chain, @order_page, 'SUPPLY_CHAIN.API.ORDER_READ', 'API', 'order:read', '查询订货宝订单', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r120, @app_supply_chain, @order_page, 'SUPPLY_CHAIN.API.ORDER_WRITE', 'API', 'order:write', '同步订货宝订单到本地', 20, 'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_resource_ui (resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at) VALUES
    (@r101, 'supply.order.order-list', '/supply-chain/order/orders', NULL, 1, 0, @seed_at, @seed_at),
    (@r102, 'supply.order.stock-up', '/supply-chain/order/stock-up', NULL, 1, 0, @seed_at, @seed_at),
    (@r103, 'supply.order.shipments', '/supply-chain/order/shipments', NULL, 1, 0, @seed_at, @seed_at),
    (@r104, 'supply.order.returns', '/supply-chain/order/returns', NULL, 1, 0, @seed_at, @seed_at),
    (@r105, 'supply.order.delivery-partners', '/supply-chain/order/delivery-partners', NULL, 1, 0, @seed_at, @seed_at),
    (@r106, 'supply.order.stats-goods', '/supply-chain/order/stats/goods', NULL, 1, 0, @seed_at, @seed_at),
    (@r107, 'supply.order.stats-pending-stock', '/supply-chain/order/stats/pending-stock', NULL, 1, 0, @seed_at, @seed_at),
    (@r108, 'supply.order.stats-shipped', '/supply-chain/order/stats/shipped', NULL, 1, 0, @seed_at, @seed_at),
    (@r109, 'supply.order.stats-pending-delivery', '/supply-chain/order/stats/pending-delivery', NULL, 1, 0, @seed_at, @seed_at),
    (@r110, 'supply.order.stats-returns', '/supply-chain/order/stats/returns', NULL, 1, 0, @seed_at, @seed_at),
    (@r111, 'supply.order.goods', '/supply-chain/order/goods', NULL, 1, 0, @seed_at, @seed_at),
    (@r112, 'supply.order.marketing', '/supply-chain/order/marketing', NULL, 1, 0, @seed_at, @seed_at),
    (@r113, 'supply.order.inventory', '/supply-chain/order/inventory', NULL, 1, 0, @seed_at, @seed_at),
    (@r114, 'supply.order.customer', '/supply-chain/order/customer', NULL, 1, 0, @seed_at, @seed_at),
    (@r115, 'supply.order.finance', '/supply-chain/order/finance', NULL, 1, 0, @seed_at, @seed_at),
    (@r116, 'supply.order.report', '/supply-chain/order/report', NULL, 1, 0, @seed_at, @seed_at),
    (@r117, 'supply.order.data', '/supply-chain/order/data', NULL, 1, 0, @seed_at, @seed_at),
    (@r118, 'supply.order.system', '/supply-chain/order/system', NULL, 1, 0, @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource.id, @seed_at, NULL
FROM iam_resource resource
WHERE resource.id IN (@r101, @r102, @r103, @r104, @r105, @r106, @r107, @r108, @r109, @r110,
                      @r111, @r112, @r113, @r114, @r115, @r116, @r117, @r118, @r119, @r120);
