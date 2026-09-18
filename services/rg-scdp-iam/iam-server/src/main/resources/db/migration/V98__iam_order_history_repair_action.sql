-- 历史商品核对使用独立动作；仍需由角色明确配置订单范围。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
SET @repair=UUID_TO_BIN('213cce14-158a-439e-a1c3-b25f0cedb936');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(@repair,@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.HISTORY.REPAIR','BUTTON','order:history:repair','核对历史商品',120,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,@repair,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
