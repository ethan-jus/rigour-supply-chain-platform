-- IAM V114：将订单与回款统计作为数据库驱动的订单菜单页面开放。
-- 前端 catalog 只负责已编译 routeKey 的白名单，不在代码中创建正式菜单。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @order_menu=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.menu');
SET @statistics=UUID_TO_BIN('bd7b6e1c-5cb7-4a9d-8d6d-4b0c6b6e1140');

INSERT INTO iam_resource(
    id,application_id,parent_id,resource_code,resource_type,permission_code,
    display_name,sort_order,status,created_at,updated_at)
VALUES(
    @statistics,@app,@order_menu,'SUPPLY_CHAIN.PAGE.ORDER_STATISTICS','PAGE',NULL,
    '订单与回款统计',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    resource_type='PAGE',
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(
    resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(
    @statistics,'supply.order.statistics','/supply-chain/order/statistics',NULL,TRUE,FALSE,
    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    route_path=VALUES(route_path),
    visible=TRUE,
    updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@statistics,UTC_TIMESTAMP(6)
  FROM iam_package_resource
 WHERE resource_id=@order_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@statistics,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource
 WHERE resource_id=@order_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(
    tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
    sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@statistics,n.id,@statistics,'PAGE','订单与回款统计',20,TRUE,
       'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n
 WHERE n.resource_id=@order_menu
   AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    visible=TRUE,
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);
