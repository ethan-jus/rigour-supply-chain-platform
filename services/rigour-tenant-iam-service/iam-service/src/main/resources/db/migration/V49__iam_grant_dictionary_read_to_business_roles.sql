-- ERP、CRM、Order 页面已经统一通过业务字典解析来源枚举。
-- 将字典只读能力授予已有任一相关查询权限的角色；字典维护权限仍只属于明确授权的管理员。
SET @changed_at = TIMESTAMP('2026-08-15 18:30:00.000000');
SET @dict_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000295');

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT role_record.tenant_id, role_record.id, @dict_read,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
  JOIN iam_role_resource current_grant
    ON current_grant.tenant_id = role_record.tenant_id
   AND current_grant.role_id = role_record.id
   AND current_grant.status = 'ACTIVE'
  JOIN iam_resource current_resource
    ON current_resource.id = current_grant.resource_id
   AND current_resource.status = 'ACTIVE'
 WHERE role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
   AND current_resource.permission_code IN (
       'erp:product:read',
       'erp:supply:read',
       'crm:customer:read',
       'order:read'
   )
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

UPDATE iam_tenant tenant_record
SET tenant_record.policy_version = tenant_record.policy_version + 1,
    tenant_record.version = tenant_record.version + 1,
    tenant_record.updated_at = @changed_at
WHERE tenant_record.status = 'ACTIVE'
  AND tenant_record.deleted_at IS NULL
  AND EXISTS (
      SELECT 1
        FROM iam_role_resource dictionary_grant
       WHERE dictionary_grant.tenant_id = tenant_record.id
         AND dictionary_grant.resource_id = @dict_read
         AND dictionary_grant.status = 'ACTIVE'
  );
