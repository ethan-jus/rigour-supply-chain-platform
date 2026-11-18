-- 运输成本动作分别配置；不为普通角色新增授权，不改变其他成本业务的确认流程。
SET @transport_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
SET @transport_page=UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a');
SET @transport_delete=UUID_TO_BIN('dc31cf89-5c93-490b-aad3-1e1363a10560');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT @transport_delete,@transport_app,@transport_page,'SUPPLY_CHAIN.COST.LOGISTICS.DELETE','BUTTON','order:logistics-cost:delete','删除已作废运输记录',50,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM iam_resource WHERE permission_code='order:logistics-cost:delete' AND deleted_at IS NULL);

UPDATE iam_resource SET display_name=CASE permission_code
 WHEN 'order:logistics-cost:read' THEN '查看运输记录'
 WHEN 'order:logistics-cost:write' THEN '新增、编辑及关联运输记录'
 WHEN 'order:logistics-cost:void' THEN '作废运输记录'
 WHEN 'order:logistics-cost:export' THEN '导出运输记录' ELSE display_name END,updated_at=UTC_TIMESTAMP(6)
WHERE parent_id=@transport_page AND deleted_at IS NULL;

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@transport_delete,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@transport_page;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT p.tenant_id,p.application_id,UUID_TO_BIN(UUID()),p.id,r.id,'BUTTON',r.display_name,r.sort_order,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node p JOIN iam_resource r ON r.parent_id=@transport_page AND r.application_id=p.application_id
WHERE p.resource_id=@transport_page AND p.deleted_at IS NULL AND r.deleted_at IS NULL AND r.status='ACTIVE'
AND r.permission_code IN ('order:logistics-cost:read','order:logistics-cost:write','order:logistics-cost:void','order:logistics-cost:export','order:logistics-cost:delete')
AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.tenant_id=p.tenant_id AND n.application_id=p.application_id AND n.resource_id=r.id AND n.deleted_at IS NULL);

UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
SET n.display_name=r.display_name,n.updated_at=UTC_TIMESTAMP(6)
WHERE r.parent_id=@transport_page AND n.deleted_at IS NULL;

-- 旧“作废”能力原本同时包含删除：迁移已有删除能力，后续可单独取消删除授权。
INSERT IGNORE INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT g.tenant_id,g.role_id,@transport_delete,g.status,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_role_resource g JOIN iam_resource r ON r.id=g.resource_id
WHERE r.permission_code='order:logistics-cost:void' AND r.deleted_at IS NULL;
INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT g.tenant_id,g.application_id,g.role_id,d.id
FROM iam_app_role_grant g JOIN iam_app_menu_node v ON v.id=g.menu_node_id AND v.tenant_id=g.tenant_id AND v.application_id=g.application_id
JOIN iam_resource r ON r.id=v.resource_id AND r.permission_code='order:logistics-cost:void'
JOIN iam_app_menu_node d ON d.tenant_id=v.tenant_id AND d.application_id=v.application_id AND d.resource_id=@transport_delete AND d.deleted_at IS NULL
WHERE v.deleted_at IS NULL;

UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@transport_app;
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE id IN (SELECT tenant_id FROM iam_app_settings WHERE application_id=@transport_app);
