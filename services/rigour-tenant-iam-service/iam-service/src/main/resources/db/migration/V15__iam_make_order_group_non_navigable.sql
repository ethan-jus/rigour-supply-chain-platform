-- IAM V15：将“订货宝”下的“订单”调整为不可跳转的二级菜单分组。
-- 订单查询只允许从三级“订货单”进入，点击“订单”本身只展开/收起菜单，不触发接口请求。

SET @seed_at = TIMESTAMP('2026-08-03 16:00:00.000000');
SET @order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');

UPDATE iam_resource
   SET resource_code = 'SUPPLY_CHAIN.MENU.ORDER_GROUP',
       resource_type = 'MENU',
       display_name = '订单',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @order_page
   AND resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_INDEX';

UPDATE iam_resource_ui
   SET route_key = 'supply.order.group',
       route_path = NULL,
       updated_at = @seed_at
 WHERE resource_id = @order_page;

UPDATE iam_tenant tenant_record
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role role_record
        WHERE role_record.tenant_id = tenant_record.id
          AND role_record.role_code = 'TENANT_SUPER_ADMIN'
          AND role_record.role_type = 'SYSTEM'
          AND role_record.status = 'ACTIVE'
          AND role_record.deleted_at IS NULL
   );
