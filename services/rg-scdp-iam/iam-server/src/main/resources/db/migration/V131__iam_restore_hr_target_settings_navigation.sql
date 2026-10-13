-- V129 删除了仍在使用的人事指标入口。恢复页面和修改动作，不恢复已取消的默认标准。
SET @target_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @target_hr=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.HR');
SET @target_page=UUID_TO_BIN('34a44d64-690d-4e6c-b52a-8bd97079e5e5');
SET @target_write=UUID_TO_BIN('019facf2-0000-7000-8000-000000000396');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES (@target_page,@target_app,@target_hr,'SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS','PAGE','hr:targets:read','指标设置',35,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@target_write,@target_app,@target_page,'SUPPLY_CHAIN.API.BI_TARGETS_WRITE','BUTTON','hr:targets:write','修改指标',40,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES (@target_page,'supply.hr.target-settings','/supply-chain/hr/target-settings','Aim',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

-- 沿用人事模块的套餐范围；内置管理员按现有机制动态获得功能，普通角色仍需显式授权。
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT p.package_version_id,r.id,UTC_TIMESTAMP(6)
FROM iam_package_resource p JOIN iam_resource r ON r.id IN (@target_page,@target_write)
WHERE p.resource_id=@target_hr;

-- 使用租户现有的人事目录节点，保留自定义目录名称、位置及其他菜单和授权。
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,icon_key,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@target_page,n.id,@target_page,'PAGE','指标设置','Aim',35,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n
WHERE n.application_id=@target_app AND n.resource_id=@target_hr AND n.deleted_at IS NULL;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@target_write,n.id,@target_write,'BUTTON','修改指标',40,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n
WHERE n.application_id=@target_app AND n.resource_id=@target_page AND n.deleted_at IS NULL;

UPDATE iam_app_settings s SET s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6)
WHERE s.application_id=@target_app AND EXISTS (
    SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=s.tenant_id
      AND n.application_id=s.application_id AND n.resource_id=@target_page
);
UPDATE iam_tenant t SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS (
    SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=t.id
      AND n.application_id=@target_app AND n.resource_id=@target_page
);
