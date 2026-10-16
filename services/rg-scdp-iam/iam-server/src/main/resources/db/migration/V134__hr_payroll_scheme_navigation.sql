-- 方案与城市任职为现有人事模块的独立页面。发布权限单独授权，不自动授予普通角色。
SET @scheme_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @scheme_hr=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.HR');
SET @scheme_page=UUID_TO_BIN('626dc030-7965-4c93-aa59-395762c78700');
SET @manager_page=UUID_TO_BIN('626dc030-7965-4c93-aa59-395762c78701');
SET @scheme_publish=UUID_TO_BIN('626dc030-7965-4c93-aa59-395762c78702');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES
(@scheme_page,@scheme_app,@scheme_hr,'SUPPLY_CHAIN.PAGE.HR_PAYROLL_SCHEMES','PAGE',NULL,'薪酬方案',65,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@manager_page,@scheme_app,@scheme_hr,'SUPPLY_CHAIN.PAGE.HR_CITY_MANAGERS','PAGE',NULL,'城市总管理',66,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@scheme_publish,@scheme_app,@scheme_page,'SUPPLY_CHAIN.API.HR_PAYROLL_SCHEME_PUBLISH','BUTTON','hr:payroll-rule:publish','发布薪酬方案',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,visible,keep_alive,created_at,updated_at) VALUES
(@scheme_page,'supply.hr.payroll-schemes','/supply-chain/hr/payroll-schemes',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@manager_page,'supply.hr.city-managers','/supply-chain/hr/city-managers',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT p.package_version_id,r.id,UTC_TIMESTAMP(6) FROM iam_package_resource p
JOIN iam_resource r ON r.id IN (@scheme_page,@manager_page,@scheme_publish) WHERE p.resource_id=@scheme_hr;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,r.id,n.id,r.id,'PAGE',r.display_name,r.sort_order,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n JOIN iam_resource r ON r.id IN (@scheme_page,@manager_page)
WHERE n.resource_id=@scheme_hr AND n.deleted_at IS NULL;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@scheme_publish,n.id,@scheme_publish,'BUTTON','发布薪酬方案',30,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n WHERE n.resource_id=@scheme_page AND n.deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@scheme_app;
UPDATE iam_tenant t SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=t.id AND n.resource_id=@scheme_page AND n.deleted_at IS NULL);
