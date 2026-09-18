-- IAM V31：销售H5拜访录音采集权限（上传片段、查询本人录音会话）。
-- 沿用 V29/V30 的资源与套餐模式，不改写历史迁移。

SET @seed_at = TIMESTAMP('2026-08-09 12:30:00.000000');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @h5_recording_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000280');
SET @h5_recording_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000281');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_recording_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.RECORDING_READ', 'API', 'sales:recording:own:read', '查询本人拜访录音', 90,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_recording_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.RECORDING_WRITE', 'API', 'sales:recording:own:write', '上传拜访录音片段', 100,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_recording_read AS resource_id
    UNION ALL SELECT @h5_recording_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @h5_recording_read AS resource_id
    UNION ALL SELECT @h5_recording_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;
