-- 单一 SCDP 产品入口；保留历史表及审计，不改写已执行迁移。
SET @changed_at = UTC_TIMESTAMP(6);
SET @scdp = (SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @sales_page = (SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.SALES_DASHBOARD');

UPDATE iam_application SET app_name='瑞盖供应链数字化平台', target_uri='/supply-chain',
    version=version+1, updated_at=@changed_at WHERE id=@scdp;

-- 移动端销售能力仍属于供应链；保留资源 ID 和既有授权关系。
-- 它们是接口权限，不将移动端页面加入 Web 菜单。
UPDATE iam_resource r JOIN iam_application a ON a.id=r.application_id
SET r.application_id=@scdp, r.parent_id=@sales_page, r.version=r.version+1, r.updated_at=@changed_at
WHERE a.app_code='FEISHU_SALES' AND r.resource_type IN ('API','BUTTON')
  AND r.permission_code LIKE 'sales:%';

-- 已初始化租户补齐可配置的移动端按钮能力；不授予任何角色，不恢复已删除节点。
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,
    display_name,sort_order,visible,status,protected_node,version,created_at,updated_at)
SELECT DISTINCT parent.tenant_id,@scdp,r.id,parent.id,r.id,'BUTTON',
    r.display_name,r.sort_order,FALSE,'ACTIVE',FALSE,0,@changed_at,@changed_at
FROM iam_app_menu_node parent
JOIN iam_resource r ON r.application_id=@scdp AND r.parent_id=@sales_page
    AND r.resource_type IN ('API','BUTTON') AND r.permission_code LIKE 'sales:%' AND r.status='ACTIVE' AND r.deleted_at IS NULL
JOIN iam_tenant_subscription sub ON sub.tenant_id=parent.tenant_id AND sub.status IN ('ACTIVE','SCHEDULED')
    AND sub.deleted_at IS NULL AND sub.effective_from<=@changed_at AND sub.effective_to>@changed_at
JOIN iam_package_resource pr ON pr.package_version_id=sub.package_version_id AND pr.resource_id=r.id
WHERE parent.application_id=@scdp AND parent.resource_id=@sales_page
    AND parent.node_type='PAGE' AND parent.status='ACTIVE' AND parent.deleted_at IS NULL
    AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node existing
        WHERE existing.tenant_id=parent.tenant_id AND existing.application_id=@scdp
            AND (existing.id=r.id OR existing.resource_id=r.id));

UPDATE iam_resource_ui ui JOIN iam_resource r ON r.id=ui.resource_id
JOIN iam_application a ON a.id=r.application_id
SET ui.visible=0, ui.version=ui.version+1, ui.updated_at=@changed_at
WHERE a.app_code <> 'SUPPLY_CHAIN';
UPDATE iam_resource r JOIN iam_application a ON a.id=r.application_id
SET r.status='DISABLED', r.version=r.version+1, r.updated_at=@changed_at
WHERE a.app_code <> 'SUPPLY_CHAIN' AND r.status='ACTIVE';
UPDATE iam_application SET status='DISABLED', version=version+1, updated_at=@changed_at
WHERE app_code <> 'SUPPLY_CHAIN' AND status='ACTIVE';

UPDATE iam_auth_session SET status='REVOKED', revoked_at=@changed_at,
    revoke_reason='SCDP_SINGLE_PRODUCT', version=version+1
WHERE principal_scope='PLATFORM' AND status='ACTIVE';
UPDATE iam_platform_user SET status='DISABLED', security_version=security_version+1,
    version=version+1, updated_at=@changed_at WHERE status <> 'DISABLED';
UPDATE iam_platform_user_credential SET status='DISABLED', version=version+1,
    updated_at=@changed_at WHERE status <> 'DISABLED';

-- 只更换 Web 客户端公开名称；内部 ID、重定向白名单、PKCE 与签名策略不变。
UPDATE iam_oauth_client old_client LEFT JOIN iam_oauth_client current_client
    ON current_client.client_id=CONCAT('rigour-scdp-', SUBSTRING(old_client.client_id, 15))
SET old_client.client_id=IF(current_client.id IS NULL, CONCAT('rigour-scdp-', SUBSTRING(old_client.client_id, 15)), old_client.client_id),
    old_client.status=IF(current_client.id IS NULL, old_client.status, 'DISABLED'),
    old_client.client_name='瑞盖供应链数字化平台', old_client.version=old_client.version+1, old_client.updated_at=@changed_at
WHERE old_client.client_id LIKE 'rigour-portal-%';
UPDATE iam_tenant SET policy_version=policy_version+1, version=version+1, updated_at=@changed_at
WHERE deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1, updated_at=@changed_at WHERE application_id=@scdp;
