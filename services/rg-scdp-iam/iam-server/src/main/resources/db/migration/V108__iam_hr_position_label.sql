-- 岗位采用单一目录，更新默认菜单文案；保留租户自行修改的名称与授权。
SET @changed_at=UTC_TIMESTAMP(6);
SET @position_page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.hr.positions');
UPDATE iam_app_settings s JOIN iam_app_menu_node n ON n.tenant_id=s.tenant_id AND n.application_id=s.application_id
SET s.version=s.version+1,s.updated_at=@changed_at WHERE n.resource_id=@position_page AND n.deleted_at IS NULL;
UPDATE iam_resource SET display_name='岗位管理',version=version+1,updated_at=@changed_at
WHERE id=@position_page AND display_name IN ('岗位职位','岗位/职位','岗位');
UPDATE iam_app_menu_node SET display_name='岗位管理',version=version+1,updated_at=@changed_at
WHERE resource_id=@position_page AND deleted_at IS NULL AND display_name IN ('岗位职位','岗位/职位','岗位');
UPDATE iam_tenant_menu_config SET display_name_override='岗位管理',version=version+1,updated_at=@changed_at
WHERE resource_id=@position_page AND display_name_override IN ('岗位职位','岗位/职位','岗位');
UPDATE iam_resource SET display_name=REPLACE(display_name,'岗位职位','岗位'),version=version+1,updated_at=@changed_at
WHERE permission_code IN ('hr:position:read','hr:position:write') AND display_name LIKE '%岗位职位%';
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=@changed_at
WHERE status='ACTIVE' AND deleted_at IS NULL;
