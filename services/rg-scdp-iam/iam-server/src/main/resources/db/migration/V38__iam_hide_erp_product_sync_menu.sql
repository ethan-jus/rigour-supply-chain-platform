-- IAM V38：移除 ERP 商品主数据下的“数据同步”业务菜单入口。
-- 同步能力与资源权限保留，仅不再作为 ERP 业务菜单展示。

SET @changed_at = TIMESTAMP('2026-08-11 17:45:00.000000');
SET @erp_product_sync_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');

UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id = @erp_product_sync_page;

UPDATE iam_tenant_menu_config
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id = @erp_product_sync_page;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
