-- 历史归属申请与审批分开授权；每个动作仍配置订单数据范围。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
SET @action=UUID_TO_BIN('56ed7b60-3c6d-4f50-a5bb-00ad20193371');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(@action,@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.ATTRIBUTION.READ','BUTTON','order:attribution:read','查看归属复核',130,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,@action,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @action=UUID_TO_BIN('23bba268-2b60-48c3-992c-53d3e6b32b92');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(@action,@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.ATTRIBUTION.PROPOSE','BUTTON','order:attribution:propose','申请归属更正',131,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,@action,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @action=UUID_TO_BIN('cb775166-14ab-435c-8acf-e23e91de765c');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(@action,@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.ATTRIBUTION.APPROVE','BUTTON','order:attribution:approve','审核归属更正',132,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,@action,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
