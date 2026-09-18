-- IAM V47：隐藏旧“批次与效期”“库存预警”入口。
-- V46 已在环境中执行，不能修改其内容；本次补充变更单独迁移。

SET @changed_at = TIMESTAMP('2026-08-15 14:30:00.000000');

UPDATE iam_resource
   SET status = 'DISABLED', updated_at = @changed_at
 WHERE id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193')
 );

UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193')
 );

UPDATE iam_tenant_menu_config config
JOIN iam_resource_ui ui ON ui.resource_id = config.resource_id
   SET config.visible = ui.visible, config.updated_at = @changed_at
 WHERE config.resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193')
 );

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
