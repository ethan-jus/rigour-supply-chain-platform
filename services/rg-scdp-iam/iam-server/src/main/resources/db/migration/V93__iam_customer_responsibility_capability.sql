-- 客户主责变更独立授权，不随普通客户资料编辑自动开放。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.crm.customers.profiles');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('a884412c-c549-5d1f-ac3b-63afc7f74084'),@app,@page,'SUPPLY_CHAIN.CRM.CUSTOMER.ASSIGN_OWNER','BUTTON','crm:customer:assign-owner','分配或移交客户主责',80,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,UUID_TO_BIN('a884412c-c549-5d1f-ac3b-63afc7f74084'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
