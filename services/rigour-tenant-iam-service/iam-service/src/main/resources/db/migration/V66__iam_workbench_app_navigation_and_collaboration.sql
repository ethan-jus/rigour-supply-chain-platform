-- IAM V66：重建门户工作台应用导航与协作权限。
--
-- 共享 DEV 已执行过同名迁移，但源文件缺失；本文件按共享 DEV 当前已落地的业务状态重建，
-- 用于 Flyway repair 后恢复源码与数据库历史的一致性。

SET @seed_at = TIMESTAMP('2026-08-20 18:00:00.000000');
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
       version = CASE
           WHEN NOT (app_name <=> '门户工作台')
             OR NOT (app_type <=> 'EXTERNAL')
             OR NOT (icon_key <=> 'app-workbench')
             OR NOT (sort_order <=> 50)
             OR NOT (launch_mode <=> 'FEISHU_DEEPLINK')
             OR NOT (target_uri <=> '/sales-workbench')
             OR NOT (status <=> 'ACTIVE')
           THEN version + 1
           ELSE version
       END,
       updated_at = @seed_at
 WHERE id = @app_workbench
   AND app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET resource_code = 'WORKBENCH.ROOT',
       display_name = '门户工作台',
       sort_order = 10,
       status = 'ACTIVE',
       version = CASE
           WHEN NOT (resource_code <=> 'WORKBENCH.ROOT')
             OR NOT (display_name <=> '门户工作台')
             OR NOT (sort_order <=> 10)
             OR NOT (status <=> 'ACTIVE')
           THEN version + 1
           ELSE version
       END,
       updated_at = @seed_at
 WHERE id = @workbench_root
   AND application_id = @app_workbench;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, version, created_at, updated_at
) VALUES
    (@workbench_home, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.HOME', 'PAGE', NULL, '工作台', 10, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_attendance, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.ATTENDANCE', 'PAGE', NULL, '外勤考勤', 20, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_targets, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.TARGETS', 'PAGE', NULL, '客户与门店', 30, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_visit, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.VISIT', 'PAGE', NULL, '拜访打卡', 40, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_track, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.TRACK', 'PAGE', NULL, '工作记录', 50, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_chat, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.CHAT', 'PAGE', NULL, '内部沟通', 60, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_profile, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.PROFILE', 'PAGE', NULL, '个人', 70, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_collaboration_im, @app_workbench, @workbench_root,
     'WORKBENCH.API.COLLABORATION_IM', 'API', 'collaboration:im:use',
     '使用内部IM', 80, 'ACTIVE', 0, @seed_at, @seed_at),
    (@workbench_collaboration_meeting, @app_workbench, @workbench_root,
     'WORKBENCH.API.COLLABORATION_MEETING', 'API', 'collaboration:meeting:use',
     '使用语音会议', 90, 'ACTIVE', 0, @seed_at, @seed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = VALUES(status),
    version = VALUES(version),
    updated_at = VALUES(updated_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@workbench_home, 'workbench.home', '/home', 'home-o', 1, 1, @seed_at, @seed_at),
    (@workbench_attendance, 'workbench.attendance', '/attendance', 'clock-o', 1, 1, @seed_at, @seed_at),
    (@workbench_targets, 'workbench.targets', '/targets', 'shop-o', 1, 0, @seed_at, @seed_at),
    (@workbench_visit, 'workbench.visit', '/visit', 'friends-o', 1, 0, @seed_at, @seed_at),
    (@workbench_track, 'workbench.track', '/track', 'location-o', 1, 1, @seed_at, @seed_at),
    (@workbench_chat, 'workbench.chat', '/chat', 'chat-o', 1, 1, @seed_at, @seed_at),
    (@workbench_profile, 'workbench.profile', '/profile', 'user-o', 1, 1, @seed_at, @seed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = VALUES(visible),
    keep_alive = VALUES(keep_alive),
    updated_at = VALUES(updated_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, workbench_resource.resource_id, @seed_at, NULL
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
SELECT DISTINCT subscription.tenant_id, workbench_page.resource_id, 1, @seed_at, @seed_at
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
    updated_at = @seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, workbench_resource.resource_id,
       'ACTIVE', @seed_at, @seed_at
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
    updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @seed_at
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
