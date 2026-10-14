-- 启用已有考勤占位资源，保留原节点及角色关联；修改权限需单独授权。
SET @attendance_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @attendance_hr=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.HR');
SET @attendance_rules=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.HR_CALENDAR_POLICIES');
SET @attendance_list=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.HR_ATTENDANCE_APPEALS');
SET @attendance_rule_write=UUID_TO_BIN('49f0fe8a-8732-4211-b06e-e174ee590a80');
SET @attendance_note_write=UUID_TO_BIN('60f9bd54-8298-490a-8770-d03cf514f8b7');

UPDATE iam_resource SET display_name='考勤规则设置',permission_code='hr:attendance-rule:read',status='ACTIVE',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@attendance_rules;
UPDATE iam_resource SET display_name='员工考勤',permission_code='hr:attendance:read',status='ACTIVE',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@attendance_list;
UPDATE iam_resource_ui SET route_key='supply.hr.attendance-rules',route_path='/supply-chain/hr/attendance-rules',visible=TRUE,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE resource_id=@attendance_rules;
UPDATE iam_resource_ui SET route_key='supply.hr.attendance',route_path='/supply-chain/hr/attendance',visible=TRUE,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE resource_id=@attendance_list;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES (@attendance_rule_write,@attendance_app,@attendance_rules,'SUPPLY_CHAIN.API.HR_ATTENDANCE_RULE_WRITE','BUTTON','hr:attendance-rule:write','设置全员应出勤',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@attendance_note_write,@attendance_app,@attendance_list,'SUPPLY_CHAIN.API.HR_ATTENDANCE_WRITE','BUTTON','hr:attendance:write','填写缺卡原因',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT p.package_version_id,r.id,UTC_TIMESTAMP(6) FROM iam_package_resource p
JOIN iam_resource r ON r.id IN (@attendance_rules,@attendance_list,@attendance_rule_write,@attendance_note_write)
WHERE p.resource_id=@attendance_hr;

-- 只更新这两个正式功能名称，保留租户其他菜单的名称、位置和可见性。
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
SET n.display_name=r.display_name,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE n.resource_id IN (@attendance_rules,@attendance_list) AND n.deleted_at IS NULL;
UPDATE iam_tenant_menu_config c JOIN iam_resource r ON r.id=c.resource_id
SET c.display_name_override=r.display_name,c.version=c.version+1,c.updated_at=UTC_TIMESTAMP(6)
WHERE c.resource_id IN (@attendance_rules,@attendance_list)
  AND c.display_name_override IN ('工作日历与考勤政策','正式考勤与申诉');

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,r.id,n.id,r.id,'BUTTON',r.display_name,10,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n JOIN iam_resource r ON r.parent_id=n.resource_id
WHERE n.application_id=@attendance_app AND n.deleted_at IS NULL AND r.id IN (@attendance_rule_write,@attendance_note_write);

UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@attendance_app;
UPDATE iam_tenant t SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=t.id AND n.application_id=@attendance_app AND n.resource_id IN (@attendance_rules,@attendance_list));
