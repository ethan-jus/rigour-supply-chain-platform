-- IAM V58：删除旧 CRM 外部员工导航资源。
--
-- 新方案口径：
-- 1. 员工、岗位、角色、组织统一归 IAM 员工中心管理。
-- 2. CRM 只保存业务归属关系中的 iam_staff_code，不再暴露 CRM 外部员工页面。
-- 3. 订货宝员工来源只保留在 iam_external_staff_binding，供同步映射和运维排查使用。

SET @changed_at = TIMESTAMP('2026-08-23 01:10:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @crm_external_staff_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000293');

CREATE TABLE IF NOT EXISTS iam_resource_cleanup_v58 (
    resource_id BINARY(16) NOT NULL,
    CONSTRAINT pk_iam_resource_cleanup_v58 PRIMARY KEY (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='V58待删除旧CRM外部员工导航资源ID';

TRUNCATE TABLE iam_resource_cleanup_v58;

INSERT IGNORE INTO iam_resource_cleanup_v58 (resource_id)
SELECT resource_record.id
  FROM iam_resource resource_record
  JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
 WHERE resource_record.application_id = @app_supply_chain
   AND (resource_record.id = @crm_external_staff_page
        OR ui_record.route_key = 'supply.crm.assignments.external-staff'
        OR ui_record.route_path = '/supply-chain/crm/assignments/external-staff');

DELETE role_resource
  FROM iam_role_resource role_resource
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = role_resource.resource_id;

DELETE package_resource
  FROM iam_package_resource package_resource
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = package_resource.resource_id;

DELETE menu_config
  FROM iam_tenant_menu_config menu_config
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = menu_config.resource_id;

UPDATE iam_resource child_resource
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = child_resource.parent_id
   SET child_resource.parent_id = NULL,
       child_resource.version = child_resource.version + 1,
       child_resource.updated_at = @changed_at;

DELETE resource_ui
  FROM iam_resource_ui resource_ui
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = resource_ui.resource_id;

DELETE resource_record
  FROM iam_resource resource_record
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = resource_record.id;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

DROP TABLE iam_resource_cleanup_v58;
