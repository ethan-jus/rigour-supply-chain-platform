-- IAM V41：订单管理不再展示“订单接入”及其子菜单。
-- 只退出正式导航，保留稳定资源和直达路由，避免在替代能力尚未完整落地前破坏历史授权与兼容入口。

SET @changed_at = TIMESTAMP('2026-08-12 18:30:00.000000');
SET @order_access = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @order_access_backstage = UUID_TO_BIN('019facf2-0000-7000-8000-000000000228');
SET @order_access_exception = UUID_TO_BIN('019facf2-0000-7000-8000-000000000229');

-- 平台默认导航隐藏，后续新租户不会再看到该分组。
UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (@order_access, @order_access_backstage, @order_access_exception);

-- 已有租户可能保存过 visible=1 的覆盖配置，需要同步收口。
UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (@order_access, @order_access_backstage, @order_access_exception);

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
