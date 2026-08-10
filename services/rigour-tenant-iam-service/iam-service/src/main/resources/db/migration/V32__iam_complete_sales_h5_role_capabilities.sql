-- IAM V32：为已经具备销售 H5 拜访写权限的普通销售角色补齐轨迹与录音能力。
-- V30/V31 只自动授权了租户超级管理员，导致已能发起拜访的普通销售在查询录音/轨迹时返回 403。
-- 以既有 sales:visit:own:write 授权作为销售 H5 角色判据，不按角色名称猜测，也不授予任何管理端权限。

SET @seed_at = TIMESTAMP('2026-08-09 14:00:00.000000');

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id,
       source_grant.role_id,
       target_resource.id,
       'ACTIVE',
       @seed_at,
       @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_role role_record
    ON role_record.tenant_id = source_grant.tenant_id
   AND role_record.id = source_grant.role_id
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
  JOIN iam_resource source_resource
    ON source_resource.id = source_grant.resource_id
   AND source_resource.permission_code = 'sales:visit:own:write'
   AND source_resource.status = 'ACTIVE'
 CROSS JOIN iam_resource target_resource
 WHERE source_grant.status = 'ACTIVE'
   AND target_resource.status = 'ACTIVE'
   AND target_resource.permission_code IN (
       'sales:track:own:read',
       'sales:recording:own:read',
       'sales:recording:own:write'
   )
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

-- 令既有会话权限版本失效；用户重新进入飞书工作台后由 IAM 签发包含新权限的会话。
UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;
