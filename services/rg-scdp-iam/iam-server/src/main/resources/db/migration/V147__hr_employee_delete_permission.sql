-- 删除员工为独立权限，已有编辑权限不自动获得删除权限。
SET @employee_delete_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @employee_delete_parent=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.HR_EMPLOYEE');
SET @employee_delete_resource=UUID_TO_BIN('9538b7bb-8de8-4df2-9d62-c2bef4566c16');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(@employee_delete_resource,@employee_delete_app,@employee_delete_parent,'SUPPLY_CHAIN.HR.SETTINGS.EMPLOYEES.DELETE','BUTTON','hr:employee:delete','删除员工',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT DISTINCT package_version_id,@employee_delete_resource,UTC_TIMESTAMP(6)
FROM iam_package_resource WHERE resource_id=@employee_delete_parent;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT tenant_id,application_id,@employee_delete_resource,id,@employee_delete_resource,'BUTTON','删除员工',30,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node WHERE resource_id=@employee_delete_parent AND deleted_at IS NULL;

UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE application_id=@employee_delete_app;
UPDATE iam_tenant t SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=t.id AND n.resource_id=@employee_delete_parent AND n.deleted_at IS NULL);
