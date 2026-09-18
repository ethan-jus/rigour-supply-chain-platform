-- IAM V39：销售本人现场门头照读写与主管敏感证据查看权限。
-- 本人能力跟随既有 sales:visit:own:write 角色；主管查看跟随 sales:visit:review，避免按角色名猜测。

SET @seed_at = TIMESTAMP('2026-08-11 11:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @evidence_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000149');
SET @h5_evidence_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000289');
SET @h5_evidence_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028a');
SET @management_evidence_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028b');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_evidence_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.EVIDENCE_READ', 'API', 'sales:evidence:own:read', '查询本人拜访照片证据', 110,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_evidence_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.EVIDENCE_WRITE', 'API', 'sales:evidence:own:write', '拍摄并上传拜访门头照', 120,
     'ACTIVE', @seed_at, @seed_at),
    (@management_evidence_read, @app_supply_chain, @evidence_page,
     'SUPPLY_CHAIN.API.SALES_EVIDENCE_READ', 'API', 'sales:evidence:sensitive:read', '查看拜访照片证据', 20,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_evidence_read AS resource_id
    UNION ALL SELECT @h5_evidence_write
    UNION ALL SELECT @management_evidence_read
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id, source_grant.role_id, target_resource.id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_resource source_resource
    ON source_resource.id = source_grant.resource_id
   AND source_resource.permission_code = 'sales:visit:own:write'
   AND source_resource.status = 'ACTIVE'
 CROSS JOIN iam_resource target_resource
 WHERE source_grant.status = 'ACTIVE'
   AND target_resource.status = 'ACTIVE'
   AND target_resource.permission_code IN ('sales:evidence:own:read', 'sales:evidence:own:write')
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id, source_grant.role_id, @management_evidence_read,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_resource source_resource
    ON source_resource.id = source_grant.resource_id
   AND source_resource.permission_code = 'sales:visit:review'
   AND source_resource.status = 'ACTIVE'
 WHERE source_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;
