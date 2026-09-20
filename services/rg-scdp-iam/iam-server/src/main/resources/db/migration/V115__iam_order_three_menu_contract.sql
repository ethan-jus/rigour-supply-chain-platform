-- IAM V115：订单域正式侧栏只保留订单列表、订单明细、订单回款三个菜单。
-- 统计页面资源若已由 V114 建立，保留资源但从正式侧栏隐藏，避免改写已执行迁移。
SET @order_list=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
SET @order_lines=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.lines');
SET @order_payments=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-payments');
SET @order_statistics=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.statistics');

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id=resource_record.id
   SET resource_record.display_name=CASE ui_record.route_key
           WHEN 'supply.order.sales-orders' THEN '订单列表'
           WHEN 'supply.order.lines' THEN '订单明细'
           WHEN 'supply.order.sales-payments' THEN '订单回款'
           ELSE resource_record.display_name
       END,
       resource_record.updated_at=UTC_TIMESTAMP(6)
 WHERE ui_record.route_key IN (
       'supply.order.sales-orders',
       'supply.order.lines',
       'supply.order.sales-payments'
 );

UPDATE iam_resource_ui
   SET visible=1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE route_key IN (
       'supply.order.sales-orders',
       'supply.order.lines',
       'supply.order.sales-payments'
 );

UPDATE iam_resource_ui
   SET visible=0,
       updated_at=UTC_TIMESTAMP(6)
 WHERE route_key='supply.order.statistics';

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui ui_record ON ui_record.resource_id=menu_config.resource_id
   SET menu_config.visible=CASE
           WHEN ui_record.route_key IN (
               'supply.order.sales-orders',
               'supply.order.lines',
               'supply.order.sales-payments') THEN 1
           WHEN ui_record.route_key='supply.order.statistics' THEN 0
           ELSE menu_config.visible
       END,
       menu_config.updated_at=UTC_TIMESTAMP(6)
 WHERE ui_record.route_key IN (
       'supply.order.sales-orders',
       'supply.order.lines',
       'supply.order.sales-payments',
       'supply.order.statistics'
 );

UPDATE iam_app_menu_node menu_node
JOIN iam_resource_ui ui_record ON ui_record.resource_id=menu_node.resource_id
   SET menu_node.display_name=CASE ui_record.route_key
           WHEN 'supply.order.sales-orders' THEN '订单列表'
           WHEN 'supply.order.lines' THEN '订单明细'
           WHEN 'supply.order.sales-payments' THEN '订单回款'
           WHEN 'supply.order.statistics' THEN '订单与回款统计'
           ELSE menu_node.display_name
       END,
       menu_node.visible=CASE
           WHEN ui_record.route_key='supply.order.statistics' THEN 0
           ELSE 1
       END,
       menu_node.updated_at=UTC_TIMESTAMP(6)
 WHERE ui_record.route_key IN (
       'supply.order.sales-orders',
       'supply.order.lines',
       'supply.order.sales-payments',
       'supply.order.statistics'
 );

UPDATE iam_role_resource role_grant
   SET role_grant.status='ACTIVE',
       role_grant.updated_at=UTC_TIMESTAMP(6)
 WHERE role_grant.resource_id IN (@order_list,@order_lines,@order_payments)
   AND role_grant.status <> 'ACTIVE';

UPDATE iam_role_resource role_grant
   SET role_grant.status='INACTIVE',
       role_grant.updated_at=UTC_TIMESTAMP(6)
 WHERE @order_statistics IS NOT NULL
   AND role_grant.resource_id=@order_statistics;
