-- IAM V13：将订货宝下的订单菜单调整为三级树。
-- 订货宝（一级）→ 订单（二级）→ 订货单等页面（三级）。订单统计保留统计页面分组。

SET @seed_at = TIMESTAMP('2026-08-03 14:30:00.000000');
SET @order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
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

-- 订单模块下挂订货单、出库发货、发货单、退货单、配送伙伴和订单统计。
UPDATE iam_resource
   SET parent_id = @order_page, version = version + 1, updated_at = @seed_at
 WHERE id IN (@r101, @r102, @r103, @r104, @r105, @r106);

-- 订单统计作为三级菜单分组，统计页面作为其下级页面。
UPDATE iam_resource
   SET parent_id = @order_page,
       resource_code = 'SUPPLY_CHAIN.MENU.ORDER_STATS',
       resource_type = 'MENU',
       display_name = '订单统计',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @r106;

UPDATE iam_resource
   SET parent_id = @r106, version = version + 1, updated_at = @seed_at
 WHERE id IN (@r107, @r108, @r109, @r110);

UPDATE iam_resource_ui
   SET route_key = 'supply.order.stats', route_path = NULL, updated_at = @seed_at
 WHERE resource_id = @r106;
