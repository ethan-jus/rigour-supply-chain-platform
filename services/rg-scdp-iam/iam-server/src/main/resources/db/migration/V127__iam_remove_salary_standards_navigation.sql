-- 移除独立薪资标准入口；薪酬规则的读写权限由提成、绩效和补贴设置共用。
SET @changed_at = UTC_TIMESTAMP(6);
SET @salary_standards = (
    SELECT r.id FROM iam_resource r
    JOIN iam_resource_ui ui ON ui.resource_id=r.id
    JOIN iam_application app ON app.id=r.application_id
    WHERE app.app_code='SUPPLY_CHAIN' AND ui.route_key='supply.hr.salary-standards'
);
SET @payroll_settings = (
    SELECT r.id FROM iam_resource r
    JOIN iam_resource_ui ui ON ui.resource_id=r.id
    JOIN iam_application app ON app.id=r.application_id
    WHERE app.app_code='SUPPLY_CHAIN' AND ui.route_key='supply.hr.payroll-settings.menu'
);

-- 先迁移共用权限节点，避免停用旧页面后丢失其他薪酬配置的授权入口。
UPDATE iam_resource
SET parent_id=@payroll_settings,version=version+1,updated_at=@changed_at
WHERE parent_id=@salary_standards AND @payroll_settings IS NOT NULL
  AND permission_code IN ('hr:payroll-rule:read','hr:payroll-rule:write');

UPDATE iam_app_menu_node button_node
JOIN iam_resource r ON r.id=button_node.resource_id
JOIN iam_app_menu_node old_page
  ON old_page.tenant_id=button_node.tenant_id
 AND old_page.application_id=button_node.application_id
 AND old_page.id=button_node.parent_id
JOIN iam_app_menu_node settings_node
  ON settings_node.tenant_id=button_node.tenant_id
 AND settings_node.application_id=button_node.application_id
 AND settings_node.resource_id=@payroll_settings
 AND settings_node.deleted_at IS NULL
SET button_node.parent_id=settings_node.id,
    button_node.version=button_node.version+1,button_node.updated_at=@changed_at
WHERE old_page.resource_id=@salary_standards AND button_node.deleted_at IS NULL
  AND r.permission_code IN ('hr:payroll-rule:read','hr:payroll-rule:write');

UPDATE iam_tenant_menu_config config_record
JOIN iam_resource r ON r.id=config_record.resource_id
SET config_record.parent_resource_id=@payroll_settings,
    config_record.version=config_record.version+1,config_record.updated_at=@changed_at
WHERE config_record.parent_resource_id=@salary_standards AND @payroll_settings IS NOT NULL
  AND r.permission_code IN ('hr:payroll-rule:read','hr:payroll-rule:write');

UPDATE iam_app_settings settings_record
JOIN iam_app_menu_node node_record
  ON node_record.tenant_id=settings_record.tenant_id
 AND node_record.application_id=settings_record.application_id
SET settings_record.version=settings_record.version+1,settings_record.updated_at=@changed_at
WHERE node_record.resource_id=@salary_standards AND node_record.deleted_at IS NULL;

UPDATE iam_resource
SET status='DISABLED',version=version+1,updated_at=@changed_at
WHERE id=@salary_standards;

UPDATE iam_resource_ui
SET visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@salary_standards;

UPDATE iam_app_menu_node
SET status='DISABLED',visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@salary_standards AND deleted_at IS NULL;

UPDATE iam_tenant_menu_config
SET visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@salary_standards;

UPDATE iam_tenant tenant_record
SET tenant_record.policy_version=tenant_record.policy_version+1,
    tenant_record.version=tenant_record.version+1,tenant_record.updated_at=@changed_at
WHERE tenant_record.status='ACTIVE' AND tenant_record.deleted_at IS NULL
  AND EXISTS (
      SELECT 1 FROM iam_app_menu_node n
      WHERE n.tenant_id=tenant_record.id AND n.resource_id=@salary_standards
        AND n.deleted_at IS NULL
  );
