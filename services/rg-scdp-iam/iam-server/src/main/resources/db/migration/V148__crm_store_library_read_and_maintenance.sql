-- Separate library visibility from maintenance, retaining only existing managers' rights and scopes.
SET @library_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @library_page=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.CRM_STORE_LIBRARY');
SET @library_maintain=UUID_TO_BIN('6e6f259c-a03d-4bb7-94ac-98ece02a5f83');

UPDATE iam_resource SET permission_code='crm:store:library-read',updated_at=UTC_TIMESTAMP(6) WHERE id=@library_page;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(@library_maintain,@library_app,@library_page,'SUPPLY_CHAIN.ACTION.CRM_STORE_MAINTAIN','BUTTON','crm:store:manage','维护门店资料',1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@library_maintain,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@library_page;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT tenant_id,application_id,UUID_TO_BIN(UUID()),id,@library_maintain,'BUTTON','维护门店资料',1,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node WHERE resource_id=@library_page AND deleted_at IS NULL;

INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT g.tenant_id,g.application_id,g.role_id,b.id FROM iam_app_role_grant g
JOIN iam_app_menu_node p ON p.tenant_id=g.tenant_id AND p.application_id=g.application_id AND p.id=g.menu_node_id
JOIN iam_app_menu_node b ON b.tenant_id=p.tenant_id AND b.application_id=p.application_id AND b.parent_id=p.id AND b.resource_id=@library_maintain
WHERE p.resource_id=@library_page AND p.deleted_at IS NULL;

INSERT INTO iam_app_scope_rule(tenant_id,application_id,id,role_id,action_code,object_type,scope_mode,department_mode,region_mode,warehouse_mode,include_descendants,version)
SELECT tenant_id,application_id,UUID_TO_BIN(UUID()),role_id,'crm:store:library-read',object_type,scope_mode,department_mode,region_mode,warehouse_mode,include_descendants,version
FROM iam_app_scope_rule WHERE application_id=@library_app AND action_code='crm:store:manage';
INSERT INTO iam_app_scope_reference(tenant_id,application_id,scope_rule_id,dimension,reference_key)
SELECT r.tenant_id,r.application_id,n.id,r.dimension,r.reference_key FROM iam_app_scope_reference r
JOIN iam_app_scope_rule p ON p.tenant_id=r.tenant_id AND p.application_id=r.application_id AND p.id=r.scope_rule_id
JOIN iam_app_scope_rule n ON n.tenant_id=p.tenant_id AND n.application_id=p.application_id AND n.role_id=p.role_id AND n.action_code='crm:store:library-read'
WHERE p.application_id=@library_app AND p.action_code='crm:store:manage';

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@library_maintain,status,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_role_resource WHERE resource_id=@library_page;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@library_app;
UPDATE iam_tenant t SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=t.id AND n.resource_id=@library_page AND n.deleted_at IS NULL);
