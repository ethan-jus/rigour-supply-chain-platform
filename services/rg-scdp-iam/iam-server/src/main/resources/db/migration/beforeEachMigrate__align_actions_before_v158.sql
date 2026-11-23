-- 全新安装的 14 项有效权限仍挂在 V158 将删除的父节点下。只迁移原始父子关系。
SET @before_scm_v158 = EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='157' AND success=1)
    AND NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='158' AND success=1);
SET @action_parents = JSON_ARRAY(
    JSON_ARRAY('API.SALES_RECORDING_PLAY','PAGE.SALES_EXCEPTION_RECORDING','PAGE.SALES_DASHBOARD'),
    JSON_ARRAY('API.SALES_POLICY_READ','PAGE.SALES_POLICY_FIELD','PAGE.SALES_POLICY_VISIT'),
    JSON_ARRAY('API.SALES_POLICY_WRITE','PAGE.SALES_POLICY_FIELD','PAGE.SALES_POLICY_VISIT'),
    JSON_ARRAY('API.SALES_POLICY_PUBLISH','PAGE.SALES_POLICY_RELEASE','PAGE.SALES_POLICY_VISIT'),
    JSON_ARRAY('API.SALES_PROFILE_WRITE','PAGE.SALES_PROFILE','MENU.SALES_ORGANIZATION'),
    JSON_ARRAY('API.SALES_IDENTITY_BIND','PAGE.SALES_ORG_SCOPE','MENU.SALES_ORGANIZATION'),
    JSON_ARRAY('API.SALES_STORE_PROJECTION_WRITE','PAGE.SALES_STORE_ASSIGNED','MENU.SALES_ORGANIZATION'),
    JSON_ARRAY('API.SALES_ASSIGNMENT_WRITE','PAGE.SALES_STORE_ASSIGNED','MENU.SALES_ORGANIZATION'),
    JSON_ARRAY('API.ERP_SUPPLY_READ','PAGE.ERP_INDEX','MENU.ERP'),
    JSON_ARRAY('API.ERP_SUPPLY_WRITE','PAGE.ERP_INDEX','MENU.ERP'),
    JSON_ARRAY('API.SALES_EVIDENCE_READ','PAGE.SALES_EXCEPTION_EVIDENCE','PAGE.SALES_DASHBOARD'),
    JSON_ARRAY('API.SALES_RISK_READ','PAGE.SALES_EXCEPTION_EVIDENCE','PAGE.SALES_DASHBOARD'),
    JSON_ARRAY('API.SALES_RISK_WRITE','PAGE.SALES_EXCEPTION_EVIDENCE','PAGE.SALES_DASHBOARD'),
    JSON_ARRAY('API.HR_PAYROLL_SCHEME_PUBLISH','PAGE.HR_PAYROLL_SCHEMES','COST_PAYROLL.SUPPLY.HR.PAYROLL-SETTINGS.MENU')
);
SET @parent_rules = 'JOIN iam_application app ON app.id=r.application_id AND app.app_code=''SUPPLY_CHAIN''
JOIN JSON_TABLE(@action_parents, ''$[*]'' COLUMNS (
    action_code VARCHAR(128) PATH ''$[0]'', old_code VARCHAR(128) PATH ''$[1]'', new_code VARCHAR(128) PATH ''$[2]''
)) m ON CAST(r.resource_code AS BINARY)=CAST(CONCAT(''SUPPLY_CHAIN.'',m.action_code) AS BINARY)
JOIN iam_resource old_parent ON old_parent.application_id=r.application_id AND CAST(old_parent.resource_code AS BINARY)=CAST(CONCAT(''SUPPLY_CHAIN.'',m.old_code) AS BINARY)
JOIN iam_resource new_parent ON new_parent.application_id=r.application_id AND CAST(new_parent.resource_code AS BINARY)=CAST(CONCAT(''SUPPLY_CHAIN.'',m.new_code) AS BINARY) ';

SET @fix_native = IF(@before_scm_v158, CONCAT('UPDATE iam_app_menu_node n
JOIN iam_resource r ON r.id=n.resource_id AND r.application_id=n.application_id ', @parent_rules,
'JOIN iam_app_menu_node old_node ON old_node.tenant_id=n.tenant_id AND old_node.application_id=n.application_id
    AND old_node.resource_id=old_parent.id AND old_node.id=n.parent_id
JOIN iam_app_menu_node new_node ON new_node.tenant_id=n.tenant_id AND new_node.application_id=n.application_id
    AND new_node.resource_id=new_parent.id AND new_node.deleted_at IS NULL
SET n.parent_id=new_node.id,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE n.node_type=''BUTTON'' AND r.resource_type IN (''API'',''BUTTON'')'), 'DO 0');
PREPARE align_native FROM @fix_native;
EXECUTE align_native;
DEALLOCATE PREPARE align_native;

SET @fix_catalog = IF(@before_scm_v158, CONCAT('UPDATE iam_resource r ', @parent_rules,
'SET r.parent_id=new_parent.id,r.version=r.version+1,r.updated_at=UTC_TIMESTAMP(6)
WHERE r.parent_id=old_parent.id AND r.resource_type IN (''API'',''BUTTON'')'), 'DO 0');
PREPARE align_catalog FROM @fix_catalog;
EXECUTE align_catalog;
DEALLOCATE PREPARE align_catalog;
