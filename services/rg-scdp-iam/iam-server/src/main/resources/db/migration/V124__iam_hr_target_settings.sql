-- 人事指标设置入口复用已有经营目标维护权限；默认标准为独立动作，不自动授予角色。
SET @target_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @target_hr=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.HR');
SET @target_page=UUID_TO_BIN('34a44d64-690d-4e6c-b52a-8bd97079e5e5');
SET @target_defaults=UUID_TO_BIN('d09a07a6-579f-4e0e-8c23-1820371d6a3a');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES (@target_page,@target_app,@target_hr,'SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS','PAGE','analytics:dashboard:read','指标设置',35,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@target_defaults,@target_app,@target_page,'SUPPLY_CHAIN.API.BI_TARGET_DEFAULTS','BUTTON','analytics:targets:defaults','维护默认指标标准',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(@target_page,'supply.hr.target-settings','/supply-chain/hr/target-settings','Aim',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT p.package_version_id,r.id,UTC_TIMESTAMP(6) FROM iam_package_resource p
JOIN iam_resource r ON r.id IN (@target_page,@target_defaults) WHERE p.resource_id=@target_hr;
INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT DISTINCT g.tenant_id,g.role_id,@target_page,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_role_resource g JOIN iam_resource r ON r.id=g.resource_id
WHERE r.permission_code='analytics:targets:write' AND g.status='ACTIVE';
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@target_page,n.id,@target_page,'PAGE','指标设置',35,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n WHERE n.resource_id=@target_hr AND n.deleted_at IS NULL;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@target_defaults,n.id,@target_defaults,'BUTTON','维护默认指标标准',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n WHERE n.resource_id=@target_page AND n.deleted_at IS NULL;
UPDATE iam_app_settings s SET version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE application_id=@target_app;
UPDATE iam_tenant SET policy_version=policy_version+1,updated_at=UTC_TIMESTAMP(6)
WHERE status='ACTIVE' AND deleted_at IS NULL;
