-- 回款风险看板与城市经营看板保持一致：已授权页面提供看板读取权限。
-- 不新增角色菜单授权，不修改已保存的数据范围，也不授予刷新或写入权限。
UPDATE iam_resource r
 JOIN iam_application a ON a.id=r.application_id AND a.app_code='SUPPLY_CHAIN'
 SET r.permission_code='analytics:dashboard:read',r.version=r.version+1,
     r.updated_at=UTC_TIMESTAMP(6)
 WHERE r.resource_code='SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK'
   AND r.resource_type='PAGE' AND r.permission_code IS NULL AND r.deleted_at IS NULL;

-- 已初始化菜单继承资源权限；递增版本以刷新已登录客户端的授权上下文。
UPDATE iam_app_settings s
 SET s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6)
 WHERE EXISTS (
     SELECT 1 FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
      WHERE n.tenant_id=s.tenant_id AND n.application_id=s.application_id
        AND n.deleted_at IS NULL AND n.permission_code IS NULL
        AND r.resource_code='SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK'
        AND r.permission_code='analytics:dashboard:read'
 );
