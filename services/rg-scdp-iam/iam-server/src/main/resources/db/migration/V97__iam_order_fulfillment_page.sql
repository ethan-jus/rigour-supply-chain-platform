-- 将已注册的履约读取能力提供为独立页面，保持权限编码及已有授权引用。
SET @node=(SELECT id FROM iam_resource WHERE permission_code='order:outbound:read');
SET @parent=(SELECT r.parent_id FROM iam_resource r JOIN iam_resource_ui u ON u.resource_id=r.id WHERE u.route_key='supply.order.sales-orders');
UPDATE iam_resource SET resource_type='PAGE',parent_id=@parent,display_name='订单出库',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@node;
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(@node,'supply.order.fulfillments','/supply-chain/order/fulfillments','Box',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
UPDATE iam_app_menu_node n JOIN iam_app_menu_node p ON p.tenant_id=n.tenant_id AND p.application_id=n.application_id AND p.resource_id=@parent SET n.node_type='PAGE',n.parent_id=p.id,n.display_name='订单出库',n.version=n.version+1 WHERE n.resource_id=@node;
