-- 删除不再使用的指标设置入口及其专属动作；不涉及 HR 指标业务数据。
SET @target_removed_at = UTC_TIMESTAMP(6);
SET @target_app = (SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @target_page = (
    SELECT id FROM iam_resource
    WHERE application_id=@target_app AND resource_code='SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS'
);
SET @target_hr = (SELECT parent_id FROM iam_resource WHERE id=@target_page);
SET @target_write = (
    SELECT id FROM iam_resource WHERE application_id=@target_app AND parent_id=@target_page
      AND resource_code='SUPPLY_CHAIN.API.BI_TARGETS_WRITE'
);
SET @target_defaults = (
    SELECT id FROM iam_resource WHERE application_id=@target_app AND parent_id=@target_page
      AND resource_code='SUPPLY_CHAIN.API.BI_TARGET_DEFAULTS'
);

-- 删除前更新受影响租户的菜单和授权版本，促使现有会话重新加载。
UPDATE iam_app_settings s
SET s.version=s.version+1,s.updated_at=@target_removed_at
WHERE s.application_id=@target_app AND EXISTS (
    SELECT 1 FROM iam_app_menu_node n
    WHERE n.tenant_id=s.tenant_id AND n.application_id=s.application_id
      AND n.resource_id IN (@target_page,@target_write,@target_defaults)
);
UPDATE iam_tenant t
SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=@target_removed_at
WHERE EXISTS (
    SELECT 1 FROM iam_app_menu_node n
    WHERE n.tenant_id=t.id AND n.application_id=@target_app
      AND n.resource_id IN (@target_page,@target_write,@target_defaults)
);

DELETE g FROM iam_app_role_grant g
JOIN iam_app_menu_node n ON n.tenant_id=g.tenant_id
 AND n.application_id=g.application_id AND n.id=g.menu_node_id
WHERE n.resource_id IN (@target_page,@target_write,@target_defaults);

-- 租户自定义的其他目录仅移到人事父目录，避免删除无关配置。
UPDATE iam_app_menu_node n
JOIN iam_app_menu_node parent_node ON parent_node.tenant_id=n.tenant_id
 AND parent_node.application_id=n.application_id AND parent_node.id=n.parent_id
JOIN iam_app_menu_node hr ON hr.tenant_id=n.tenant_id
 AND hr.application_id=n.application_id AND hr.resource_id=@target_hr AND hr.deleted_at IS NULL
SET n.parent_id=hr.id,n.version=n.version+1,n.updated_at=@target_removed_at
WHERE parent_node.resource_id IN (@target_page,@target_write,@target_defaults)
  AND (n.resource_id IS NULL OR n.resource_id NOT IN (@target_page,@target_write,@target_defaults));

DELETE FROM iam_app_menu_node WHERE resource_id IN (@target_write,@target_defaults);
DELETE FROM iam_app_menu_node WHERE resource_id=@target_page;
DELETE FROM iam_tenant_menu_config WHERE resource_id IN (@target_page,@target_write,@target_defaults);
UPDATE iam_tenant_menu_config
SET parent_resource_id=@target_hr,version=version+1,updated_at=@target_removed_at
WHERE parent_resource_id IN (@target_page,@target_write,@target_defaults);
UPDATE iam_tenant_menu_group
SET parent_resource_id=@target_hr,version=version+1,updated_at=@target_removed_at
WHERE parent_resource_id IN (@target_page,@target_write,@target_defaults);
DELETE FROM iam_role_resource WHERE resource_id IN (@target_page,@target_write,@target_defaults);
DELETE FROM iam_package_resource WHERE resource_id IN (@target_page,@target_write,@target_defaults);
DELETE FROM iam_resource_ui WHERE resource_id IN (@target_page,@target_write,@target_defaults);
DELETE FROM iam_resource WHERE id IN (@target_write,@target_defaults);
DELETE FROM iam_resource WHERE id=@target_page;
