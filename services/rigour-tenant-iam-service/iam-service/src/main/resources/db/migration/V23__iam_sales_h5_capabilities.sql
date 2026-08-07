-- IAM V23：销售H5纵向切片所需的最小API权限。
-- 不复用或改写V18历史资源；管理后台权限和H5作业权限分开维护。

SET @seed_at = TIMESTAMP('2026-08-06 19:00:00.000000');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @h5_context_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000271');
SET @h5_target_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000272');
SET @h5_work_day_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000273');
SET @h5_location_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000274');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_context_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.CONTEXT_READ', 'API', 'sales:context:read', '读取销售作业上下文', 10,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_target_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.VISIT_TARGET_READ', 'API', 'sales:visit-target:read', '读取销售拜访目标', 20,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_work_day_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.WORK_DAY_WRITE', 'API', 'sales:work-day:write', '执行销售签到签退', 30,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_location_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.LOCATION_WRITE', 'API', 'sales:location:write', '提交销售定位证据', 40,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_context_read AS resource_id
    UNION ALL SELECT @h5_target_read
    UNION ALL SELECT @h5_work_day_write
    UNION ALL SELECT @h5_location_write
) added_resources;

-- 现有租户超级管理员可用于联调；普通销售角色仍需按租户权限流程显式授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @h5_context_read AS resource_id
    UNION ALL SELECT @h5_target_read
    UNION ALL SELECT @h5_work_day_write
    UNION ALL SELECT @h5_location_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;
