-- 月度薪酬作为唯一核算入口；沿用既有读、核算、确认、发放权限，不新增角色授权。
SET @changed_at = UTC_TIMESTAMP(6);
SET @payroll = (
    SELECT r.id FROM iam_resource r
    JOIN iam_resource_ui ui ON ui.resource_id=r.id
    JOIN iam_application app ON app.id=r.application_id
    WHERE app.app_code='SUPPLY_CHAIN' AND ui.route_key='supply.hr.payroll'
);

UPDATE iam_resource
SET display_name='月度薪酬',version=version+1,updated_at=@changed_at
WHERE id=@payroll;

UPDATE iam_app_menu_node
SET display_name='月度薪酬',version=version+1,updated_at=@changed_at
WHERE resource_id=@payroll AND deleted_at IS NULL;

UPDATE iam_tenant_menu_config
SET display_name_override='月度薪酬',version=version+1,updated_at=@changed_at
WHERE resource_id=@payroll AND display_name_override='薪酬列表';

-- 三个旧页面仅为占位入口，原始资源及授权关联保留用于审计。
UPDATE iam_resource r
JOIN iam_resource_ui ui ON ui.resource_id=r.id
JOIN iam_application app ON app.id=r.application_id AND app.app_code='SUPPLY_CHAIN'
SET r.status='DISABLED',r.version=r.version+1,r.updated_at=@changed_at,
    ui.visible=0,ui.version=ui.version+1,ui.updated_at=@changed_at
WHERE ui.route_key IN ('supply.hr.payroll-commission','supply.hr.performance','supply.hr.monthly-close');

UPDATE iam_app_menu_node n
JOIN iam_resource_ui ui ON ui.resource_id=n.resource_id
JOIN iam_application app ON app.id=n.application_id AND app.app_code='SUPPLY_CHAIN'
SET n.status='DISABLED',n.visible=0,n.version=n.version+1,n.updated_at=@changed_at
WHERE n.deleted_at IS NULL
  AND ui.route_key IN ('supply.hr.payroll-commission','supply.hr.performance','supply.hr.monthly-close');

UPDATE iam_tenant_menu_config c
JOIN iam_resource r ON r.id=c.resource_id
JOIN iam_resource_ui ui ON ui.resource_id=r.id
JOIN iam_application app ON app.id=r.application_id AND app.app_code='SUPPLY_CHAIN'
SET c.visible=0,c.version=c.version+1,c.updated_at=@changed_at
WHERE ui.route_key IN ('supply.hr.payroll-commission','supply.hr.performance','supply.hr.monthly-close');

UPDATE iam_app_settings s
JOIN iam_application app ON app.id=s.application_id AND app.app_code='SUPPLY_CHAIN'
SET s.version=s.version+1,s.updated_at=@changed_at
WHERE EXISTS (
    SELECT 1 FROM iam_app_menu_node n
    WHERE n.tenant_id=s.tenant_id AND n.application_id=s.application_id
      AND n.resource_id=@payroll AND n.deleted_at IS NULL
);

UPDATE iam_tenant t
SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=@changed_at
WHERE t.status='ACTIVE' AND t.deleted_at IS NULL
  AND EXISTS (
      SELECT 1 FROM iam_app_menu_node n
      WHERE n.tenant_id=t.id AND n.resource_id=@payroll AND n.deleted_at IS NULL
  );
