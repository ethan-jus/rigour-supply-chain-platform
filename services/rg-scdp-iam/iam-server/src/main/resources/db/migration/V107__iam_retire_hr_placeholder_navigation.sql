-- HR 员工档案已有正式页面。旧占位页曾仅在资源 UI 中隐藏，
-- 租户已保存的 visible=1 会将其再次展示；从资源及租户菜单同时停用。
-- 保留历史角色引用，不修改正式员工档案的自定义名称、层级与授权。
SET @changed_at = UTC_TIMESTAMP(6);
SET @old_hr_page = (
    SELECT r.id FROM iam_resource r
    JOIN iam_resource_ui ui ON ui.resource_id=r.id
    JOIN iam_application app ON app.id=r.application_id
    WHERE app.app_code='SUPPLY_CHAIN' AND ui.route_key='supply.hr.index'
);

UPDATE iam_app_settings settings_record
JOIN iam_app_menu_node node_record
  ON node_record.tenant_id=settings_record.tenant_id
 AND node_record.application_id=settings_record.application_id
SET settings_record.version=settings_record.version+1,
    settings_record.updated_at=@changed_at
WHERE node_record.resource_id=@old_hr_page AND node_record.deleted_at IS NULL;

UPDATE iam_resource
SET status='DISABLED',version=version+1,updated_at=@changed_at
WHERE id=@old_hr_page;

UPDATE iam_resource_ui
SET visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@old_hr_page;

UPDATE iam_app_menu_node
SET status='DISABLED',visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@old_hr_page AND deleted_at IS NULL;

UPDATE iam_tenant_menu_config
SET visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@old_hr_page;

UPDATE iam_tenant
SET policy_version=policy_version+1,version=version+1,updated_at=@changed_at
WHERE status='ACTIVE' AND deleted_at IS NULL;
