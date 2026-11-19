-- App 本人跟进归属原作业页。沿用本人拜访的读、写角色范围，各动作可分别取消。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
CREATE TEMPORARY TABLE scm_followup_action (
 source_code VARCHAR(128) PRIMARY KEY, permission_code VARCHAR(128), capability VARCHAR(128), display_name VARCHAR(64), sort_order INT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO scm_followup_action VALUES
 ('sales:visit:own:read','sales:followup:self:read','SUPPLY_CHAIN.APP.MY_FOLLOWUPS','我的跟进',201),
 ('sales:visit:own:write','sales:followup:self:respond','SUPPLY_CHAIN.APP.FOLLOWUP_RESPONSE','反馈结果',202);
START TRANSACTION;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
 SELECT UNHEX(MD5(f.permission_code)),@app,old.parent_id,f.capability,'BUTTON',f.permission_code,f.display_name,f.sort_order,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
 FROM scm_followup_action f JOIN iam_resource old ON old.application_id=@app AND BINARY old.permission_code=BINARY f.source_code AND old.deleted_at IS NULL
 WHERE NOT EXISTS (SELECT 1 FROM iam_resource r WHERE r.id=UNHEX(MD5(f.permission_code)));
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
 SELECT pkg.package_version_id,UNHEX(MD5(f.permission_code)),UTC_TIMESTAMP(6)
 FROM scm_followup_action f JOIN iam_resource old ON old.application_id=@app AND BINARY old.permission_code=BINARY f.source_code AND old.deleted_at IS NULL
 JOIN iam_package_resource pkg ON pkg.resource_id=old.id;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status)
 SELECT n.tenant_id,@app,UNHEX(MD5(f.permission_code)),n.parent_id,UNHEX(MD5(f.permission_code)),'BUTTON',f.display_name,f.sort_order,0,n.status
 FROM scm_followup_action f JOIN iam_resource old ON old.application_id=@app AND BINARY old.permission_code=BINARY f.source_code AND old.deleted_at IS NULL
 JOIN iam_app_menu_node n ON n.application_id=@app AND n.resource_id=old.id AND n.deleted_at IS NULL
 WHERE NOT EXISTS (SELECT 1 FROM iam_app_menu_node dest WHERE dest.tenant_id=n.tenant_id AND dest.application_id=@app AND dest.resource_id=UNHEX(MD5(f.permission_code)));
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
 SELECT g.tenant_id,@app,g.role_id,UNHEX(MD5(f.permission_code))
 FROM scm_followup_action f JOIN iam_resource old ON old.application_id=@app AND BINARY old.permission_code=BINARY f.source_code AND old.deleted_at IS NULL AND old.status='ACTIVE'
 JOIN iam_app_menu_node n ON n.resource_id=old.id AND n.application_id=@app AND n.status='ACTIVE' AND n.deleted_at IS NULL
 JOIN iam_app_role_grant g ON g.tenant_id=n.tenant_id AND g.application_id=n.application_id AND g.menu_node_id=n.id;
INSERT IGNORE INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
 SELECT grant_row.tenant_id,grant_row.role_id,UNHEX(MD5(f.permission_code)),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
 FROM scm_followup_action f JOIN iam_resource old ON old.application_id=@app AND BINARY old.permission_code=BINARY f.source_code
 JOIN iam_role_resource grant_row ON grant_row.resource_id=old.id AND grant_row.status='ACTIVE'
 WHERE old.deleted_at IS NULL AND old.status='ACTIVE';
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@app;
UPDATE iam_tenant t JOIN iam_app_settings s ON s.tenant_id=t.id AND s.application_id=@app SET t.policy_version=t.policy_version+1;
COMMIT;
DROP TEMPORARY TABLE scm_followup_action;
