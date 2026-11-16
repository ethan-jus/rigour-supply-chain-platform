-- V153 已登记全局能力；现有应用角色仍需要对应的动作节点。
SET @field_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
SET @field_parent=UUID_TO_BIN('019facf2-0000-7000-8000-000000000149');

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT p.tenant_id,p.application_id,UUID_TO_BIN(UUID()),p.id,r.id,'BUTTON',r.display_name,r.sort_order,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node p JOIN iam_resource r ON r.application_id=p.application_id AND r.parent_id=@field_parent
WHERE p.application_id=@field_app AND p.resource_id=@field_parent AND p.deleted_at IS NULL
AND r.permission_code IN ('sales:risk:read','sales:risk:write') AND r.status='ACTIVE' AND r.deleted_at IS NULL
AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=p.tenant_id AND n.application_id=p.application_id AND n.resource_id=r.id AND n.deleted_at IS NULL);

-- 受保护初始化管理员沿用全部启用能力；普通角色须在应用授权中明确选择。
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT a.tenant_id,a.application_id,a.id,n.id FROM iam_app_role a
JOIN iam_app_menu_node n ON n.tenant_id=a.tenant_id AND n.application_id=a.application_id
JOIN iam_resource r ON r.id=n.resource_id
WHERE a.application_id=@field_app AND a.role_code='SUPPLY_BOOTSTRAP_ADMIN' AND a.protected_role=TRUE
AND a.status='ACTIVE' AND a.deleted_at IS NULL AND n.status='ACTIVE' AND n.deleted_at IS NULL
AND r.permission_code IN ('sales:risk:read','sales:risk:write');

UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE application_id=@field_app AND EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=iam_app_settings.tenant_id AND n.application_id=@field_app AND n.resource_id=@field_parent AND n.deleted_at IS NULL);
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=iam_tenant.id AND n.application_id=@field_app AND n.resource_id=@field_parent AND n.deleted_at IS NULL);
