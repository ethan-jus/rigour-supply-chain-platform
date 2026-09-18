-- IAM V14：将V12/V13新增的订货宝订单菜单授权给已存在的租户超级管理员。
-- 新资源先进入套餐不会自动补写既有角色，否则旧租户登录后只能看到迁移前的订单入口。

SET @seed_at = TIMESTAMP('2026-08-03 15:10:00.000000');
SET @r101 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @r102 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @r103 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @r104 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @r105 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @r106 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @r107 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000107');
SET @r108 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000108');
SET @r109 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000109');
SET @r110 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000110');
SET @r111 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @r112 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000112');
SET @r113 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000113');
SET @r114 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000114');
SET @r115 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000115');
SET @r116 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000116');
SET @r117 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000117');
SET @r118 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000118');
SET @r119 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000119');
SET @r120 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000120');

INSERT INTO iam_role_resource
    (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, package_resource.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
   AND package_resource.resource_id IN (
       @r101, @r102, @r103, @r104, @r105, @r106, @r107, @r108, @r109,
       @r110, @r111, @r112, @r113, @r114, @r115, @r116, @r117, @r118,
       @r119, @r120
   )
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @seed_at;

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
