-- 补齐供应链应用的动作节点；全局IAM资源授权不能替代应用内角色节点。
SET @hll_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
SET @hll_page=UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500010');

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT p.tenant_id,p.application_id,UUID_TO_BIN(UUID()),p.id,r.id,'BUTTON',r.display_name,r.sort_order,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node p JOIN iam_resource r ON r.application_id=p.application_id AND r.parent_id=@hll_page
WHERE p.application_id=@hll_app AND p.resource_id=@hll_page AND p.deleted_at IS NULL
AND r.permission_code IN ('integration:huolala:read','integration:huolala:write','integration:huolala:sync','order:logistics-cost:manage-imports')
AND r.status='ACTIVE' AND r.deleted_at IS NULL
AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=p.tenant_id AND n.application_id=p.application_id AND n.resource_id=r.id AND n.deleted_at IS NULL);

-- 只补齐现有受保护初始化管理员；普通业务、只读及城市角色不自动获得租户级来源管理。
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT r.tenant_id,r.application_id,r.id,n.id FROM iam_app_role r
JOIN iam_app_menu_node n ON n.tenant_id=r.tenant_id AND n.application_id=r.application_id
JOIN iam_resource a ON a.id=n.resource_id
WHERE r.application_id=@hll_app AND r.role_code='SUPPLY_BOOTSTRAP_ADMIN' AND r.protected_role=TRUE
AND r.status='ACTIVE' AND r.deleted_at IS NULL AND n.status='ACTIVE' AND n.deleted_at IS NULL
AND (a.id=@hll_page OR a.parent_id=@hll_page);

UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE application_id=@hll_app AND EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=iam_app_settings.tenant_id AND n.application_id=@hll_app AND n.resource_id=@hll_page AND n.deleted_at IS NULL);
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=iam_tenant.id AND n.application_id=@hll_app AND n.resource_id=@hll_page AND n.deleted_at IS NULL);
