-- IAM V30：销售管理维护 API 与 H5 本人轨迹查询权限。
-- 沿用 V18/V29 的资源与套餐模式，不改写历史迁移；普通角色仍需按租户权限流程显式授权。

SET @seed_at = TIMESTAMP('2026-08-09 12:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @r133 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000133');
SET @r139 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000139');
SET @r141 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000141');
SET @h5_track_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027b');
SET @admin_profile_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027c');
SET @admin_identity_bind = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027d');
SET @admin_store_projection_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027e');
SET @admin_assignment_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027f');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_track_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.TRACK_READ', 'API', 'sales:track:own:read', '查询本人当日轨迹', 80,
     'ACTIVE', @seed_at, @seed_at),
    (@admin_profile_write, @app_supply_chain, @r139,
     'SUPPLY_CHAIN.API.SALES_PROFILE_WRITE', 'API', 'sales:profile:write', '维护销售画像', 20,
     'ACTIVE', @seed_at, @seed_at),
    (@admin_identity_bind, @app_supply_chain, @r141,
     'SUPPLY_CHAIN.API.SALES_IDENTITY_BIND', 'API', 'sales:identity:bind', '绑定销售身份', 20,
     'ACTIVE', @seed_at, @seed_at),
    (@admin_store_projection_write, @app_supply_chain, @r133,
     'SUPPLY_CHAIN.API.SALES_STORE_PROJECTION_WRITE', 'API', 'sales:store-projection:write',
     '维护门店投影（临时前置）', 20, 'ACTIVE', @seed_at, @seed_at),
    (@admin_assignment_write, @app_supply_chain, @r133,
     'SUPPLY_CHAIN.API.SALES_ASSIGNMENT_WRITE', 'API', 'sales:assignment:write',
     '维护门店归属（临时前置）', 30, 'ACTIVE', @seed_at, @seed_at);

-- 新资源进入现行标准套餐；历史套餐版本不改字段，只补充资源关系。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_track_read AS resource_id
    UNION ALL SELECT @admin_profile_write
    UNION ALL SELECT @admin_identity_bind
    UNION ALL SELECT @admin_store_projection_write
    UNION ALL SELECT @admin_assignment_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- 现有租户超级管理员用于联调；普通销售与管理角色仍需按租户权限流程显式授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @h5_track_read AS resource_id
    UNION ALL SELECT @admin_profile_write
    UNION ALL SELECT @admin_identity_bind
    UNION ALL SELECT @admin_store_projection_write
    UNION ALL SELECT @admin_assignment_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;
