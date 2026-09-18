-- IAM V35：为 V34 新增的 ERP 商品中心菜单补齐既有租户默认配置。
-- V22 之后新增资源不会自动产生 iam_tenant_menu_config，导航查询的 INNER JOIN
-- 会因此过滤新菜单及其子页面；本迁移只补缺失配置，不覆盖租户已有的隐藏设置。

SET @changed_at = TIMESTAMP('2026-08-10 09:30:00.000000');
SET @erp_attributes_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000285');
SET @erp_sync_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');
SET @erp_tags_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resource_record.id,
       resource_ui.visible, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
  JOIN iam_resource_ui resource_ui
    ON resource_ui.resource_id = resource_record.id
  JOIN (
      SELECT @erp_attributes_menu AS resource_id
      UNION ALL SELECT @erp_sync_page
      UNION ALL SELECT @erp_tags_page
  ) added_resources
    ON added_resources.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
   AND resource_record.status = 'ACTIVE'
   AND resource_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
