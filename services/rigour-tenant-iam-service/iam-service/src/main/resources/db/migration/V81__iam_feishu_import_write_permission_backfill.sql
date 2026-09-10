-- IAM V81：补齐飞书导入中心写入权限。
--
-- V79 已经为飞书导入中心创建 import/write 两个 API 资源，其中页面已有角色只自动继承 import。
-- 前端目前不区分“只预检”和“可正式导入”两个入口，因此给已拥有飞书导入页面的角色补齐 write 权限，
-- 避免出现页面可见、预检通过、正式导入被权限拦截的状态。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @feishu_import_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000380');
SET @feishu_write_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000382');

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT page_grant.tenant_id, page_grant.role_id, @feishu_write_api,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource page_grant
 WHERE page_grant.resource_id = @feishu_import_page
   AND page_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;
