-- 已授权城市经营/销售业绩页面必须具备其前端路由及读取接口要求的权限。
-- 不新增菜单授权、不授予刷新/导入动作，也不调整角色已保存的数据范围。
UPDATE iam_resource r
 JOIN iam_application a ON a.id=r.application_id AND a.app_code='SUPPLY_CHAIN'
 SET r.permission_code='analytics:dashboard:read',r.version=r.version+1,
     r.updated_at=UTC_TIMESTAMP(6)
 WHERE r.resource_code IN ('SUPPLY_CHAIN.PAGE.BI_SALES','SUPPLY_CHAIN.PAGE.BI_CITY_OPERATING')
   AND r.resource_type='PAGE' AND r.permission_code IS NULL AND r.deleted_at IS NULL;

-- 已初始化菜单通过 COALESCE 继承资源权限；递增版本使已登录客户端刷新授权上下文。
UPDATE iam_app_settings s
 SET s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6)
 WHERE EXISTS (
     SELECT 1 FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
      WHERE n.tenant_id=s.tenant_id AND n.application_id=s.application_id
        AND n.deleted_at IS NULL AND n.permission_code IS NULL
        AND r.resource_code IN ('SUPPLY_CHAIN.PAGE.BI_SALES','SUPPLY_CHAIN.PAGE.BI_CITY_OPERATING')
        AND r.permission_code='analytics:dashboard:read'
 );
