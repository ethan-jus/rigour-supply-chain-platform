-- 客户类型等级价页面：本地维护商品规格 × 客户类型的等级价，权限与菜单一并注册。
-- 订货宝接口不提供等级价读取，数据由本地维护和导入建立；脚本可重复执行。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @erp_master_data_menu=UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @page=UUID_TO_BIN('019facf2-0000-7000-8000-000000000303');
SET @price_read=UUID_TO_BIN('019facf2-0000-7000-8000-000000000304');
SET @price_write=UUID_TO_BIN('019facf2-0000-7000-8000-000000000305');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES
 (@page,@app,@erp_master_data_menu,'SUPPLY_CHAIN.PAGE.ERP_CUSTOMER_TYPE_PRICES','PAGE',NULL,'客户类型等级价',70,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
 (@price_read,@app,@erp_master_data_menu,'SUPPLY_CHAIN.API.ERP_CUSTOMER_TYPE_PRICE_READ','API','erp:product-price:read','查询客户类型等级价',71,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
 (@price_write,@app,@erp_master_data_menu,'SUPPLY_CHAIN.API.ERP_CUSTOMER_TYPE_PRICE_WRITE','API','erp:product-price:write','维护客户类型等级价',72,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),resource_type=VALUES(resource_type),permission_code=VALUES(permission_code),display_name=VALUES(display_name),sort_order=VALUES(sort_order),status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(@page,'supply.erp.master-data.customer-type-prices','/supply-chain/erp/master-data/customer-type-prices',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE route_path=VALUES(route_path),visible=TRUE,updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT DISTINCT p.package_version_id,r.resource_id,UTC_TIMESTAMP(6)
  FROM iam_package_resource p
  CROSS JOIN (SELECT @page AS resource_id UNION ALL SELECT @price_read UNION ALL SELECT @price_write) r
 WHERE p.resource_id=@erp_master_data_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT rr.tenant_id,rr.role_id,r.resource_id,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource rr
  CROSS JOIN (SELECT @page AS resource_id UNION ALL SELECT @price_read UNION ALL SELECT @price_write) r
 WHERE rr.resource_id=@erp_master_data_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@page,n.id,@page,'PAGE','客户类型等级价',70,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n
 WHERE n.resource_id=@erp_master_data_menu AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),display_name=VALUES(display_name),sort_order=VALUES(sort_order),visible=TRUE,status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);

UPDATE iam_tenant SET policy_version=policy_version+1, version=version+1, updated_at=UTC_TIMESTAMP(6)
 WHERE status='ACTIVE' AND deleted_at IS NULL;
