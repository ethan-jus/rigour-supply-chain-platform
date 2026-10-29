-- Administrative reference data is global; department access remains tenant-owned in IAM.
-- Ordinary roles receive no automatic grants to change territorial authorization.
SET @territory_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @territory_parent=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.SETTINGS');
SET @territory_page=UUID_TO_BIN('18c7b1ae-5bf5-49df-8284-4489c44e2682');
SET @territory_write=UUID_TO_BIN('28c7b1ae-5bf5-49df-8284-4489c44e2682');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(@territory_page,@territory_app,@territory_parent,'SUPPLY_CHAIN.PAGE.STORE_TERRITORIES','PAGE','supply:territory:read','客户地域权限',85,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@territory_write,@territory_app,@territory_page,'SUPPLY_CHAIN.ACTION.STORE_TERRITORIES_WRITE','BUTTON','supply:territory:write','维护部门客户地域',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,visible,keep_alive,created_at,updated_at)
VALUES(@territory_page,'supply.settings.store-territories','/supply-chain/settings/store-territories',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT DISTINCT p.package_version_id,r.id,UTC_TIMESTAMP(6)
FROM iam_package_resource p JOIN iam_resource r ON r.id IN (@territory_page,@territory_write)
WHERE p.resource_id=@territory_parent;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@territory_page,n.id,@territory_page,'PAGE','客户地域权限',85,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n WHERE n.resource_id=@territory_parent AND n.deleted_at IS NULL;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@territory_write,n.id,@territory_write,'BUTTON','维护部门客户地域',10,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n WHERE n.resource_id=@territory_page AND n.deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@territory_app;
UPDATE iam_tenant t SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=t.id AND n.resource_id=@territory_page AND n.deleted_at IS NULL);
