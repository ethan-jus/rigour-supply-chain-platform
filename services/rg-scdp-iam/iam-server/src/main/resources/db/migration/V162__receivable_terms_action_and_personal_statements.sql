-- 合同账期由具备专项权限的人员维护。工资条只向绑定的员工提供已确认个人数据。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
SET @orders=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
SET @terms=UNHEX(MD5('scm-action:order:receivable-plan:write'));
SET @selfpay=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.sales.pay-statements');
SET @hrboard=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.hr.sales-payroll');
SET @publish=UNHEX(MD5('scm-action:hr:payroll:publish'));
SET @fundpage=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.bi.receivables');
SET @fundarchive=UNHEX(MD5('scm-action:bi:archive-receivables'));
START TRANSACTION;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT @terms,@app,@orders,'SUPPLY_CHAIN.ACTION.ORDER.RECEIVABLE_PLAN','BUTTON','order:receivable-plan:write','维护账期',160,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE @orders IS NOT NULL AND NOT EXISTS(SELECT 1 FROM iam_resource WHERE id=@terms);
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@terms,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@orders;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status)
SELECT p.tenant_id,@app,@terms,p.id,@terms,'BUTTON',r.display_name,r.sort_order,FALSE,'ACTIVE'
FROM iam_app_menu_node p JOIN iam_resource r ON r.id=@terms WHERE p.application_id=@app AND p.resource_id=@orders AND p.deleted_at IS NULL
AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=p.tenant_id AND n.application_id=@app AND n.resource_id=@terms);
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT a.tenant_id,@app,a.id,n.id FROM iam_app_role a JOIN iam_app_menu_node n
ON n.tenant_id=a.tenant_id AND n.application_id=a.application_id AND n.resource_id=@terms AND n.deleted_at IS NULL
WHERE a.application_id=@app AND a.protected_role=TRUE AND a.role_code='SUPPLY_BOOTSTRAP_ADMIN' AND a.status='ACTIVE' AND a.deleted_at IS NULL;
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT g.tenant_id,@app,g.role_id,n.id FROM iam_app_role_grant g
JOIN iam_app_menu_node old ON old.tenant_id=g.tenant_id AND old.application_id=g.application_id AND old.id=g.menu_node_id AND old.status='ACTIVE' AND old.deleted_at IS NULL
JOIN iam_resource r ON r.id=old.resource_id AND r.permission_code='sales:context:read' AND r.status='ACTIVE' AND r.deleted_at IS NULL
JOIN iam_app_menu_node n ON n.tenant_id=g.tenant_id AND n.application_id=@app AND n.resource_id=@selfpay AND n.status='ACTIVE' AND n.deleted_at IS NULL
WHERE g.application_id=@app;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT @publish,@app,@hrboard,'SUPPLY_CHAIN.ACTION.HR.PUBLISH_STATEMENT','BUTTON','hr:payroll:publish','公布工资条',160,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE @hrboard IS NOT NULL AND NOT EXISTS(SELECT 1 FROM iam_resource WHERE id=@publish);
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@publish,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hrboard;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status)
SELECT p.tenant_id,@app,@publish,p.id,@publish,'BUTTON',r.display_name,r.sort_order,FALSE,'ACTIVE'
FROM iam_app_menu_node p JOIN iam_resource r ON r.id=@publish WHERE p.application_id=@app AND p.resource_id=@hrboard AND p.deleted_at IS NULL
AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=p.tenant_id AND n.application_id=@app AND n.resource_id=@publish);
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT a.tenant_id,@app,a.id,n.id FROM iam_app_role a JOIN iam_app_menu_node n
ON n.tenant_id=a.tenant_id AND n.application_id=a.application_id AND n.resource_id=@publish AND n.deleted_at IS NULL
WHERE a.application_id=@app AND a.protected_role=TRUE AND a.role_code='SUPPLY_BOOTSTRAP_ADMIN' AND a.status='ACTIVE' AND a.deleted_at IS NULL;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT @fundarchive,@app,@fundpage,'SUPPLY_CHAIN.ACTION.BI.ARCHIVE_RECEIVABLES','BUTTON','analytics:receivables:archive','归档',160,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE @fundpage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM iam_resource WHERE id=@fundarchive);
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@fundarchive,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@fundpage;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status)
SELECT p.tenant_id,@app,@fundarchive,p.id,@fundarchive,'BUTTON',r.display_name,r.sort_order,FALSE,'ACTIVE'
FROM iam_app_menu_node p JOIN iam_resource r ON r.id=@fundarchive WHERE p.application_id=@app AND p.resource_id=@fundpage AND p.deleted_at IS NULL
AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=p.tenant_id AND n.application_id=@app AND n.resource_id=@fundarchive);
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT a.tenant_id,@app,a.id,n.id FROM iam_app_role a JOIN iam_app_menu_node n
ON n.tenant_id=a.tenant_id AND n.application_id=a.application_id AND n.resource_id=@fundarchive AND n.deleted_at IS NULL
WHERE a.application_id=@app AND a.protected_role=TRUE AND a.role_code='SUPPLY_BOOTSTRAP_ADMIN' AND a.status='ACTIVE' AND a.deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@app;
UPDATE iam_tenant t JOIN iam_app_settings s ON s.tenant_id=t.id AND s.application_id=@app SET t.policy_version=t.policy_version+1;
COMMIT;
