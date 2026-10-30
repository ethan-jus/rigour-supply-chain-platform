-- Public reference data and customer territory authorization are separate entries.
-- Existing role grants are preserved; no ordinary role is automatically granted access.
SET @reference_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @reference_settings=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.SETTINGS');
SET @reference_group=UUID_TO_BIN('5bde30c3-a278-4ae3-b8ad-d2962d6c3ac8');
SET @reference_admin=UUID_TO_BIN('11b9d871-40ef-48fb-b1eb-39df70f81603');
SET @reference_dictionary=(SELECT resource_id FROM iam_resource_ui WHERE route_path='/supply-chain/settings/numbering-dictionaries');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES
(@reference_group,@reference_app,@reference_settings,'SUPPLY_CHAIN.MENU.PUBLIC_REFERENCE','MENU',NULL,'公共资料',90,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@reference_admin,@reference_app,@reference_group,'SUPPLY_CHAIN.PAGE.ADMIN_DIVISIONS','PAGE','supply:admin-division:read','行政区划',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES
(@reference_group,'supply.settings.public-reference',NULL,'FolderOpened',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@reference_admin,'supply.settings.admin-divisions','/supply-chain/settings/admin-divisions','MapLocation',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
UPDATE iam_resource SET parent_id=@reference_group,sort_order=20,updated_at=UTC_TIMESTAMP(6)
WHERE id=@reference_dictionary AND parent_id=@reference_settings;

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT DISTINCT p.package_version_id,r.id,UTC_TIMESTAMP(6)
FROM iam_package_resource p JOIN iam_resource r ON r.id IN (@reference_group,@reference_admin)
WHERE p.resource_id=@reference_settings;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@reference_group,n.id,@reference_group,'MENU','公共资料',90,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n WHERE n.resource_id=@reference_settings AND n.deleted_at IS NULL;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@reference_admin,n.id,@reference_admin,'PAGE','行政区划',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n WHERE n.resource_id=@reference_group AND n.deleted_at IS NULL;
-- Move only the default dictionary location, leaving tenant-customized grouping intact.
UPDATE iam_app_menu_node d
JOIN iam_app_menu_node s ON s.tenant_id=d.tenant_id AND s.application_id=d.application_id AND s.id=d.parent_id AND s.resource_id=@reference_settings
JOIN iam_app_menu_node g ON g.tenant_id=d.tenant_id AND g.application_id=d.application_id AND g.resource_id=@reference_group AND g.deleted_at IS NULL
SET d.parent_id=g.id,d.sort_order=20,d.updated_at=UTC_TIMESTAMP(6)
WHERE d.resource_id=@reference_dictionary AND d.deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@reference_app;
UPDATE iam_tenant t SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=t.id AND n.resource_id=@reference_admin AND n.deleted_at IS NULL);
