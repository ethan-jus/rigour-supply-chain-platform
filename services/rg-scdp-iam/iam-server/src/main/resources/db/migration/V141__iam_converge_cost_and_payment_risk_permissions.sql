-- 两种真实 V139 历史向前收敛；保留角色授权、数据范围及原版迁移历史。
UPDATE iam_resource r
SET r.permission_code='analytics:cost-dashboard:read', r.display_name='成本数据看板',
    r.version=r.version+1, r.updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS (SELECT 1 FROM iam_resource_ui u WHERE u.resource_id=r.id AND u.route_key='supply.bi.city-cost')
  AND (NOT (r.permission_code <=> 'analytics:cost-dashboard:read') OR r.display_name<>'成本数据看板');

UPDATE iam_app_menu_node n
SET n.display_name='成本数据看板', n.updated_at=UTC_TIMESTAMP(6)
WHERE n.deleted_at IS NULL AND n.display_name<>'成本数据看板'
  AND EXISTS (SELECT 1 FROM iam_resource_ui u WHERE u.resource_id=n.resource_id AND u.route_key='supply.bi.city-cost');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT UUID_TO_BIN('cb40e17e-9782-47ec-9c77-a7e54893e782'),r.application_id,r.id,
       'SUPPLY_CHAIN.BI.COST_DASHBOARD.EXPORT','BUTTON','analytics:cost-dashboard:export','导出成本看板',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_resource r JOIN iam_resource_ui u ON u.resource_id=r.id
JOIN iam_application a ON a.id=r.application_id AND a.app_code='SUPPLY_CHAIN'
WHERE u.route_key='supply.bi.city-cost'
  AND NOT EXISTS (SELECT 1 FROM iam_resource e WHERE e.id=UUID_TO_BIN('cb40e17e-9782-47ec-9c77-a7e54893e782'));

INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT p.package_version_id,e.id,UTC_TIMESTAMP(6)
FROM iam_resource e JOIN iam_package_resource p ON p.resource_id=e.parent_id
WHERE e.resource_code='SUPPLY_CHAIN.BI.COST_DASHBOARD.EXPORT'
  AND NOT EXISTS (SELECT 1 FROM iam_package_resource x WHERE x.package_version_id=p.package_version_id AND x.resource_id=e.id);

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,e.id,n.id,e.id,'BUTTON','导出成本看板',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_resource e JOIN iam_app_menu_node n ON n.resource_id=e.parent_id
WHERE e.resource_code='SUPPLY_CHAIN.BI.COST_DASHBOARD.EXPORT' AND n.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM iam_app_menu_node x WHERE x.tenant_id=n.tenant_id AND x.application_id=n.application_id AND x.id=e.id);

UPDATE iam_resource r
SET r.permission_code='analytics:dashboard:read',r.version=r.version+1,r.updated_at=UTC_TIMESTAMP(6)
WHERE r.resource_code='SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK' AND r.resource_type='PAGE'
  AND r.permission_code IS NULL AND r.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM iam_application a WHERE a.id=r.application_id AND a.app_code='SUPPLY_CHAIN');

-- 刷新菜单和在线授权版本，不写入任何角色资源/权限授权。
UPDATE iam_app_settings s SET s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS (SELECT 1 FROM iam_application a WHERE a.id=s.application_id AND a.app_code='SUPPLY_CHAIN');
UPDATE iam_tenant t SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS (SELECT 1 FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
              JOIN iam_resource_ui u ON u.resource_id=r.id
              WHERE n.tenant_id=t.id AND n.deleted_at IS NULL AND u.route_key='supply.bi.city-cost');
