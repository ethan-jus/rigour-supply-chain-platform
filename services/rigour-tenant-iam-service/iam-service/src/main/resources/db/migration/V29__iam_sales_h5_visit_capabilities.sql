-- IAM V29：销售H5拜访纵向切片所需的API权限。
-- 附近门店（高德）、创建拜访/签退、本人拜访查询；沿用V23的H5应用与标准套餐，不改历史迁移。

SET @seed_at = TIMESTAMP('2026-08-08 12:00:00.000000');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @h5_visit_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000278');
SET @h5_visit_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000279');
SET @h5_poi_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027a');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_visit_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.VISIT_READ', 'API', 'sales:visit:own:read', '查询本人拜访', 50,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_visit_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.VISIT_WRITE', 'API', 'sales:visit:own:write', '创建拜访和签退', 60,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_poi_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.POI_READ', 'API', 'sales:poi:read', '查询附近门店', 70,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_visit_read AS resource_id
    UNION ALL SELECT @h5_visit_write
    UNION ALL SELECT @h5_poi_read
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- 现有租户超级管理员用于联调；普通销售角色仍需按租户权限流程显式授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @h5_visit_read AS resource_id
    UNION ALL SELECT @h5_visit_write
    UNION ALL SELECT @h5_poi_read
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
