-- 升级既有成本看板页面权限；导出为独立动作，不给已有角色自动扩大导出权限。
SET @cost_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @cost_page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.bi.city-cost');
SET @cost_export=UUID_TO_BIN('cb40e17e-9782-47ec-9c77-a7e54893e782');
UPDATE iam_resource SET permission_code='analytics:cost-dashboard:read',display_name='成本数据看板',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@cost_page;
UPDATE iam_app_menu_node SET display_name='成本数据看板',updated_at=UTC_TIMESTAMP(6) WHERE resource_id=@cost_page AND deleted_at IS NULL;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(@cost_export,@cost_app,@cost_page,'SUPPLY_CHAIN.BI.COST_DASHBOARD.EXPORT','BUTTON','analytics:cost-dashboard:export','导出成本看板',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@cost_export,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@cost_page;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT tenant_id,application_id,@cost_export,id,@cost_export,'BUTTON','导出成本看板',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node WHERE resource_id=@cost_page AND deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@cost_app;
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE id IN (SELECT tenant_id FROM iam_app_menu_node WHERE resource_id=@cost_page AND deleted_at IS NULL);
