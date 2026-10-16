-- 六类成本入口；办公室复用既有租赁动作与范围，不增加业务写权限。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @cost=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.cost.menu');
SET @rental=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.cost.warehouse-rentals');
SET @office=UUID_TO_BIN('8321ac8b-54a1-5143-bd1b-69d4b84d17d0');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(@office,@app,@cost,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.OFFICE-RENTALS','PAGE',NULL,'办公室费用',50,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(@office,'supply.cost.office-rentals','/supply-chain/cost/office-rentals',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@office,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@rental;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT tenant_id,application_id,@office,parent_id,@office,'PAGE','办公室费用',50,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node WHERE resource_id=@rental AND deleted_at IS NULL;
-- 仅复制既有场地页面的可见授权。对应角色原本已可访问办公室租赁记录。
INSERT IGNORE INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@office,status,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_role_resource WHERE resource_id=@rental;
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT g.tenant_id,g.application_id,g.role_id,@office FROM iam_app_role_grant g
JOIN iam_app_menu_node n ON n.tenant_id=g.tenant_id AND n.application_id=g.application_id AND n.id=g.menu_node_id
WHERE n.resource_id=@rental AND n.deleted_at IS NULL;
UPDATE iam_resource r JOIN iam_resource_ui u ON u.resource_id=r.id
SET r.display_name=CASE u.route_key WHEN 'supply.cost.purchases' THEN '进货记录' WHEN 'supply.cost.losses' THEN '损耗记录'
 WHEN 'supply.cost.logistics' THEN '运费' WHEN 'supply.cost.warehouse-rentals' THEN '仓储费用' WHEN 'supply.cost.marketing' THEN '营销费用' ELSE r.display_name END,
 r.sort_order=CASE u.route_key WHEN 'supply.cost.purchases' THEN 10 WHEN 'supply.cost.losses' THEN 20 WHEN 'supply.cost.logistics' THEN 30
 WHEN 'supply.cost.warehouse-rentals' THEN 40 WHEN 'supply.cost.marketing' THEN 60 ELSE r.sort_order END,r.updated_at=UTC_TIMESTAMP(6)
WHERE u.route_key IN ('supply.cost.purchases','supply.cost.losses','supply.cost.logistics','supply.cost.warehouse-rentals','supply.cost.marketing');
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id JOIN iam_resource_ui u ON u.resource_id=r.id
SET n.display_name=r.display_name,n.sort_order=r.sort_order,n.updated_at=UTC_TIMESTAMP(6)
WHERE u.route_key LIKE 'supply.cost.%' AND n.deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@app;
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE id IN (SELECT tenant_id FROM iam_app_menu_node WHERE resource_id=@cost AND deleted_at IS NULL);
