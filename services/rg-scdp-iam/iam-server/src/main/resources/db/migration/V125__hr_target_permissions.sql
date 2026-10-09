-- 指标归属 HR；沿用已有目标维护授权，不修改任何角色的数据范围。
SET @target_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @target_page=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS');
SET @target_write=(SELECT id FROM iam_resource WHERE permission_code='analytics:targets:write' LIMIT 1);
UPDATE iam_resource SET permission_code='hr:targets:read',updated_at=UTC_TIMESTAMP(6) WHERE id=@target_page;
UPDATE iam_resource SET parent_id=@target_page,permission_code='hr:targets:write',display_name='修改指标',updated_at=UTC_TIMESTAMP(6) WHERE id=@target_write;
UPDATE iam_app_menu_node n JOIN iam_app_menu_node p ON p.tenant_id=n.tenant_id AND p.application_id=n.application_id AND p.resource_id=@target_page AND p.deleted_at IS NULL
SET n.parent_id=p.id,n.display_name='修改指标',n.updated_at=UTC_TIMESTAMP(6)
WHERE n.resource_id=@target_write AND n.deleted_at IS NULL;
-- 已有写权限的角色同时获得 HR 指标页面读取权限；保留 ALL/CUSTOM/DEPARTMENT/SELF 原范围。
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT g.tenant_id,g.application_id,g.role_id,p.id
FROM iam_app_role_grant g JOIN iam_app_menu_node w ON w.tenant_id=g.tenant_id AND w.application_id=g.application_id AND w.id=g.menu_node_id AND w.resource_id=@target_write
JOIN iam_app_menu_node p ON p.tenant_id=g.tenant_id AND p.application_id=g.application_id AND p.resource_id=@target_page AND p.deleted_at IS NULL;
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
SET n.status='DISABLED',n.visible=FALSE,n.updated_at=UTC_TIMESTAMP(6)
WHERE r.permission_code='analytics:targets:defaults';
UPDATE iam_resource SET status='DISABLED',updated_at=UTC_TIMESTAMP(6) WHERE permission_code='analytics:targets:defaults';
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@target_app;
UPDATE iam_tenant SET policy_version=policy_version+1,updated_at=UTC_TIMESTAMP(6) WHERE status='ACTIVE' AND deleted_at IS NULL;
