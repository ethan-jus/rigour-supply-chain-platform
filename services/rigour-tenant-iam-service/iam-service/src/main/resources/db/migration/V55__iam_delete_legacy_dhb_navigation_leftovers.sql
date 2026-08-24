-- IAM V55：清理 V54 遗漏的旧订货宝导航残留。
--
-- V17 已将 supply.dinghuobao.* 改名为 supply.dhb.*，V54 只删除了旧长名称，
-- 导致运行时导航仍返回 supply.dhb.menu 以及 legacy-dhb 档案页。前端按已注册
-- routeKey 失败关闭，所以这里补齐清理并保留“外部同步 / 订货宝同步中心”单入口。

SET @changed_at = TIMESTAMP('2026-08-21 18:35:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @integration_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @integration_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
SET @integration_sync_control_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000300');

CREATE TABLE IF NOT EXISTS iam_resource_cleanup_v55 (
    resource_id BINARY(16) NOT NULL,
    CONSTRAINT pk_iam_resource_cleanup_v55 PRIMARY KEY (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='V55待删除旧订货宝导航资源ID';

TRUNCATE TABLE iam_resource_cleanup_v55;

-- 外部同步根菜单沿用历史资源 ID，但 routeKey 必须收敛到新前端注册表。
UPDATE iam_resource
   SET display_name = '外部同步',
       sort_order = 90,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_menu;

UPDATE iam_resource
   SET parent_id = @integration_menu,
       display_name = '订货宝同步',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_sync_control_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.sync-control.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_sync_control_menu;

UPDATE iam_resource
   SET parent_id = @integration_sync_control_menu,
       display_name = '订货宝同步中心',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_overview_page
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.overview',
       route_path = '/supply-chain/integration',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_overview_page;

INSERT IGNORE INTO iam_resource_cleanup_v55 (resource_id)
SELECT DISTINCT ui.resource_id
  FROM iam_resource_ui ui
  JOIN iam_resource resource_record ON resource_record.id = ui.resource_id
 WHERE resource_record.application_id = @app_supply_chain
   AND ui.resource_id NOT IN (@integration_menu, @integration_sync_control_menu, @integration_overview_page)
   AND (
       ui.route_key LIKE 'supply.dhb.%'
       OR ui.route_key IN (
           'supply.integration.legacy-dhb.menu',
           'supply.integration.dhb-products',
           'supply.integration.dhb-skus',
           'supply.integration.dhb-specifications'
       )
   );

DELETE role_resource
  FROM iam_role_resource role_resource
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = role_resource.resource_id;

DELETE package_resource
  FROM iam_package_resource package_resource
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = package_resource.resource_id;

DELETE menu_config
  FROM iam_tenant_menu_config menu_config
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = menu_config.resource_id;

UPDATE iam_resource child_resource
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = child_resource.parent_id
   SET child_resource.parent_id = NULL,
       child_resource.version = child_resource.version + 1,
       child_resource.updated_at = @changed_at;

UPDATE iam_resource resource_record
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = resource_record.id
   SET resource_record.parent_id = NULL,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at;

DELETE resource_ui
  FROM iam_resource_ui resource_ui
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = resource_ui.resource_id;

DELETE resource_record
  FROM iam_resource resource_record
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = resource_record.id;

UPDATE iam_tenant_menu_config
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@integration_menu, @integration_sync_control_menu, @integration_overview_page);

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

DROP TABLE iam_resource_cleanup_v55;
