-- IAM V54：物理删除旧订货宝镜像菜单和旧 Order 分散菜单。
--
-- V52 已经将这些入口隐藏；本迁移按 route_key 审计后删除资源、租户菜单配置、
-- 套餐授权和角色授权，避免前端注册表继续背旧页面兼容债。

SET @changed_at = TIMESTAMP('2026-08-21 10:40:00.000000');

CREATE TABLE IF NOT EXISTS iam_resource_cleanup_v54 (
    resource_id BINARY(16) NOT NULL,
    CONSTRAINT pk_iam_resource_cleanup_v54 PRIMARY KEY (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='V54待删除旧菜单资源ID';

TRUNCATE TABLE iam_resource_cleanup_v54;

INSERT IGNORE INTO iam_resource_cleanup_v54 (resource_id)
SELECT DISTINCT ui.resource_id
  FROM iam_resource_ui ui
 WHERE ui.route_key LIKE 'supply.order.%'
   AND ui.route_key NOT IN ('supply.order.menu', 'supply.order.sales-orders');

INSERT IGNORE INTO iam_resource_cleanup_v54 (resource_id)
SELECT DISTINCT ui.resource_id
  FROM iam_resource_ui ui
 WHERE ui.route_key LIKE 'supply.dinghuobao.%'
    OR ui.route_key IN (
        'supply.integration.raw-data',
        'supply.integration.connections',
        'supply.integration.sync-tasks',
        'supply.integration.sync-logs',
        'supply.integration.retries',
        'supply.integration.field-mappings',
        'supply.integration.reconciliation',
        'supply.integration.sovereignty',
        'supply.integration.sync-batches',
        'supply.integration.external-id-mappings'
    );

DELETE role_resource
  FROM iam_role_resource role_resource
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = role_resource.resource_id;

DELETE package_resource
  FROM iam_package_resource package_resource
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = package_resource.resource_id;

DELETE menu_config
  FROM iam_tenant_menu_config menu_config
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = menu_config.resource_id;

UPDATE iam_resource child_resource
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = child_resource.parent_id
   SET child_resource.parent_id = NULL,
       child_resource.version = child_resource.version + 1,
       child_resource.updated_at = @changed_at;

UPDATE iam_resource resource_record
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = resource_record.id
   SET resource_record.parent_id = NULL,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at;

DELETE resource_ui
  FROM iam_resource_ui resource_ui
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = resource_ui.resource_id;

DELETE resource_record
  FROM iam_resource resource_record
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = resource_record.id;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

DROP TABLE iam_resource_cleanup_v54;
