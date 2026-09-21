-- IAM V117：新增「发票管理」页面菜单（订单管理下），财务集中处理待开票。
-- 页面可见性跟随订单管理菜单；操作仍由 order:invoice:write 控制。前端 catalog 通过 routeKey 映射到已编译页面。
-- 仅新增，不修改已执行的迁移。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @order_menu=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.menu');
SET @invoices=UUID_TO_BIN('e1582a53-c489-423e-8b58-40a6bbc04b37');

INSERT INTO iam_resource(
    id,application_id,parent_id,resource_code,resource_type,permission_code,
    display_name,sort_order,status,created_at,updated_at)
VALUES(
    @invoices,@app,@order_menu,'SUPPLY_CHAIN.PAGE.ORDER_INVOICES','PAGE',NULL,
    '发票管理',25,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
    @invoices,'supply.order.invoices','/supply-chain/order/invoices',NULL,TRUE,FALSE,
    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    route_path=VALUES(route_path),
    visible=TRUE,
    updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@invoices,UTC_TIMESTAMP(6)
  FROM iam_package_resource
 WHERE resource_id=@order_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@invoices,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource
 WHERE resource_id=@order_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(
    tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
    sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@invoices,n.id,@invoices,'PAGE','发票管理',25,TRUE,
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
