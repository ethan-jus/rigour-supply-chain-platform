-- IAM V43：CRM 不再展示“客户 360”子菜单。
-- 只退出正式导航，保留稳定资源与历史授权，避免破坏已发放权限关系。

SET @changed_at = TIMESTAMP('2026-08-13 10:10:00.000000');
SET @crm_customer_360 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000203');

-- 平台默认导航隐藏，后续新租户不会再看到该页面。
UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_360;

-- 已有租户可能保存过 visible=1 的覆盖配置，需要同步收口。
UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_360;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
