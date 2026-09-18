-- 订单明细页面：订单列表/发货单同源的注册式菜单，页面组件与路由白名单在 Web 端 fail-closed 校验。
-- 资源、套餐、角色授权与租户菜单节点一并补齐，脚本可重复执行。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @order_menu=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.menu');
SET @lines=UUID_TO_BIN('a48ab364-aa49-40c1-97c5-edcb74623530');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(@lines,@app,@order_menu,'SUPPLY_CHAIN.PAGE.ORDER_SALES_LINES','PAGE',NULL,'订单明细',15,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),resource_type='PAGE',display_name=VALUES(display_name),sort_order=VALUES(sort_order),status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(@lines,'supply.order.lines','/supply-chain/order/lines',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE route_path=VALUES(route_path),visible=TRUE,updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@lines,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@order_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@lines,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource WHERE resource_id=@order_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@lines,n.id,@lines,'PAGE','订单明细',15,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n WHERE n.resource_id=@order_menu AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),display_name=VALUES(display_name),sort_order=VALUES(sort_order),visible=TRUE,status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);
