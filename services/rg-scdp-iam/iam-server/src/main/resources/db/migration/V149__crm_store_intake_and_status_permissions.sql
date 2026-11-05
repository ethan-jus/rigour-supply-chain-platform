-- One intake action is shared by public pool and merchant library; keep existing node IDs and grants.
SET @intake_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @intake_public=(SELECT id FROM iam_resource WHERE application_id=@intake_app AND permission_code='crm:pool:public-read');
SET @intake_create=(SELECT id FROM iam_resource WHERE application_id=@intake_app AND permission_code='crm:lead:create');
SET @status_parent=(SELECT id FROM iam_resource WHERE application_id=@intake_app AND permission_code='crm:pool:private-read');
SET @status_visit=(SELECT id FROM iam_resource WHERE application_id=@intake_app AND permission_code='crm:store:visit-write');
SET @status_resource=UUID_TO_BIN('8be20b76-0c06-4eb7-b2e1-1030df639cda');

UPDATE iam_resource SET parent_id=@intake_public,display_name='新增门店',updated_at=UTC_TIMESTAMP(6) WHERE id=@intake_create;
UPDATE iam_app_menu_node b JOIN iam_app_menu_node p ON p.tenant_id=b.tenant_id AND p.application_id=b.application_id
AND p.resource_id=@intake_public AND p.deleted_at IS NULL
SET b.parent_id=p.id,b.display_name='新增门店',b.updated_at=UTC_TIMESTAMP(6)
WHERE b.application_id=@intake_app AND b.resource_id=@intake_create AND b.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(@status_resource,@intake_app,@status_parent,'SUPPLY_CHAIN.ACTION.CRM_STORE_STATUS_WRITE','BUTTON','crm:store:status-write','核实门店经营状态',23,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@status_resource,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@status_visit;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT tenant_id,application_id,UUID_TO_BIN(UUID()),id,@status_resource,'BUTTON','核实门店经营状态',23,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node WHERE application_id=@intake_app AND resource_id=@status_parent AND deleted_at IS NULL;

-- Only explicitly authorized visit operators receive this independent owner-only action.
-- Read-only roles and library readers do not gain creation, maintenance or status-write rights.
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT g.tenant_id,g.application_id,g.role_id,b.id FROM iam_app_role_grant g
JOIN iam_app_menu_node v ON v.tenant_id=g.tenant_id AND v.application_id=g.application_id AND v.id=g.menu_node_id
JOIN iam_app_menu_node b ON b.tenant_id=v.tenant_id AND b.application_id=v.application_id AND b.resource_id=@status_resource AND b.deleted_at IS NULL
WHERE v.application_id=@intake_app AND v.resource_id=@status_visit AND v.status='ACTIVE' AND v.deleted_at IS NULL;
INSERT INTO iam_app_scope_rule(tenant_id,application_id,id,role_id,action_code,object_type,scope_mode,department_mode,region_mode,warehouse_mode,include_descendants,version)
SELECT s.tenant_id,s.application_id,UUID_TO_BIN(UUID()),s.role_id,'crm:store:status-write',s.object_type,s.scope_mode,s.department_mode,s.region_mode,s.warehouse_mode,s.include_descendants,s.version
FROM iam_app_scope_rule s WHERE s.application_id=@intake_app AND s.action_code='crm:store:visit-write'
AND EXISTS(SELECT 1 FROM iam_app_role_grant g JOIN iam_app_menu_node b ON b.tenant_id=g.tenant_id AND b.application_id=g.application_id AND b.id=g.menu_node_id
WHERE g.tenant_id=s.tenant_id AND g.application_id=s.application_id AND g.role_id=s.role_id AND b.resource_id=@status_resource);
INSERT INTO iam_app_scope_reference(tenant_id,application_id,scope_rule_id,dimension,reference_key)
SELECT r.tenant_id,r.application_id,n.id,r.dimension,r.reference_key FROM iam_app_scope_reference r
JOIN iam_app_scope_rule p ON p.tenant_id=r.tenant_id AND p.application_id=r.application_id AND p.id=r.scope_rule_id
JOIN iam_app_scope_rule n ON n.tenant_id=p.tenant_id AND n.application_id=p.application_id AND n.role_id=p.role_id AND n.action_code='crm:store:status-write'
WHERE p.application_id=@intake_app AND p.action_code='crm:store:visit-write';
INSERT INTO iam_app_member_role_scope(tenant_id,application_id,user_id,role_id,scope_rule_id,dimension,reference_key)
SELECT r.tenant_id,r.application_id,r.user_id,r.role_id,n.id,r.dimension,r.reference_key FROM iam_app_member_role_scope r
JOIN iam_app_scope_rule p ON p.tenant_id=r.tenant_id AND p.application_id=r.application_id AND p.id=r.scope_rule_id
JOIN iam_app_scope_rule n ON n.tenant_id=p.tenant_id AND n.application_id=p.application_id AND n.role_id=p.role_id AND n.action_code='crm:store:status-write'
WHERE p.application_id=@intake_app AND p.action_code='crm:store:visit-write';
INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@status_resource,status,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_role_resource WHERE resource_id=@status_visit AND status='ACTIVE';

UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@intake_app;
UPDATE iam_tenant t SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=t.id AND n.application_id=@intake_app AND n.resource_id IN (@status_resource,@intake_create) AND n.deleted_at IS NULL);
