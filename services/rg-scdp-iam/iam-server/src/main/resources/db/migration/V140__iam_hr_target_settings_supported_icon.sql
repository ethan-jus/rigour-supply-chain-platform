-- 历史指标菜单使用了不在内置清单中的 Aim，保存任意菜单时会被整树校验拦截。
-- 仅替换该历史默认值，保留用户已选图标、菜单授权及数据范围。
UPDATE iam_app_settings s
 SET s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6)
 WHERE EXISTS (
     SELECT 1 FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
      WHERE n.tenant_id=s.tenant_id AND n.application_id=s.application_id
        AND n.deleted_at IS NULL AND n.icon_key='Aim'
        AND r.resource_code='SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS'
 );

UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
 SET n.icon_key='TrendCharts',n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
 WHERE r.resource_code='SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS'
   AND n.deleted_at IS NULL AND n.icon_key='Aim';

UPDATE iam_resource_ui u JOIN iam_resource r ON r.id=u.resource_id
 SET u.icon_key='TrendCharts',u.updated_at=UTC_TIMESTAMP(6)
 WHERE r.resource_code='SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS' AND u.icon_key='Aim';
