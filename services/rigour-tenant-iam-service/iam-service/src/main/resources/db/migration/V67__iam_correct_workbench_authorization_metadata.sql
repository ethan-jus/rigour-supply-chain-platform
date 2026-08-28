-- IAM V67：校正门户工作台授权元数据。
--
-- V66 已建立 Workbench 页面与协作权限；本迁移按共享 DEV 已执行历史补齐一次
-- 应用、资源、套餐、租户菜单和超级管理员授权的幂等校正。

SET @changed_at = TIMESTAMP('2026-08-24 17:15:34.000000');
SET @app_workbench = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

SET @workbench_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @workbench_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000343');
SET @workbench_attendance = UUID_TO_BIN('019facf2-0000-7000-8000-000000000344');
SET @workbench_targets = UUID_TO_BIN('019facf2-0000-7000-8000-000000000345');
SET @workbench_visit = UUID_TO_BIN('019facf2-0000-7000-8000-000000000346');
SET @workbench_track = UUID_TO_BIN('019facf2-0000-7000-8000-000000000347');
SET @workbench_chat = UUID_TO_BIN('019facf2-0000-7000-8000-000000000348');
SET @workbench_profile = UUID_TO_BIN('019facf2-0000-7000-8000-000000000349');
SET @workbench_collaboration_im = UUID_TO_BIN('019facf2-0000-7000-8000-00000000034a');
SET @workbench_collaboration_meeting = UUID_TO_BIN('019facf2-0000-7000-8000-00000000034b');

UPDATE iam_application
   SET app_name = '门户工作台',
       app_type = 'EXTERNAL',
       icon_key = 'app-workbench',
       sort_order = 50,
       launch_mode = 'FEISHU_DEEPLINK',
       target_uri = '/sales-workbench',
       status = 'ACTIVE',
       updated_at = @changed_at
 WHERE id = @app_workbench
   AND app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET resource_code = 'WORKBENCH.ROOT',
       display_name = '门户工作台',
       sort_order = 10,
       status = 'ACTIVE',
       updated_at = @changed_at
 WHERE id = @workbench_root
   AND application_id = @app_workbench;

UPDATE iam_resource
   SET parent_id = @workbench_root,
       resource_type = 'PAGE',
       permission_code = NULL,
       display_name = CASE id
           WHEN @workbench_home THEN '工作台'
           WHEN @workbench_attendance THEN '外勤考勤'
           WHEN @workbench_targets THEN '客户与门店'
           WHEN @workbench_visit THEN '拜访打卡'
           WHEN @workbench_track THEN '工作记录'
           WHEN @workbench_chat THEN '内部沟通'
           WHEN @workbench_profile THEN '个人'
           ELSE display_name
       END,
       sort_order = CASE id
           WHEN @workbench_home THEN 10
           WHEN @workbench_attendance THEN 20
           WHEN @workbench_targets THEN 30
           WHEN @workbench_visit THEN 40
           WHEN @workbench_track THEN 50
           WHEN @workbench_chat THEN 60
           WHEN @workbench_profile THEN 70
           ELSE sort_order
       END,
       status = 'ACTIVE',
       updated_at = @changed_at
 WHERE application_id = @app_workbench
   AND id IN (
       @workbench_home,
       @workbench_attendance,
       @workbench_targets,
       @workbench_visit,
       @workbench_track,
       @workbench_chat,
       @workbench_profile
   );

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, workbench_resource.resource_id, @changed_at, NULL
  FROM (
    SELECT @workbench_root AS resource_id
    UNION ALL SELECT @workbench_home
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
    UNION ALL SELECT @workbench_collaboration_im
    UNION ALL SELECT @workbench_collaboration_meeting
  ) workbench_resource
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, workbench_page.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @workbench_home AS resource_id
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
  ) workbench_page
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, workbench_resource.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @workbench_root AS resource_id
    UNION ALL SELECT @workbench_home
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
    UNION ALL SELECT @workbench_collaboration_im
    UNION ALL SELECT @workbench_collaboration_meeting
  ) workbench_resource
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_tenant_menu_config menu_config
        WHERE menu_config.tenant_id = tenant_record.id
          AND menu_config.resource_id IN (
              @workbench_home,
              @workbench_attendance,
              @workbench_targets,
              @workbench_visit,
              @workbench_track,
              @workbench_chat,
              @workbench_profile
          )
          AND menu_config.visible = 1
   );
