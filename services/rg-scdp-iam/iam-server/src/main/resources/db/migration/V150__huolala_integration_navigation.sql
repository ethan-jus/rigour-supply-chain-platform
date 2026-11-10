-- 货拉拉连接与租户级待关联队列仅默认授予现有租户超级管理员，其他角色显式授权。
SET @hll_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
SET @hll_parent=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.integration.sync-control.menu');
SET @hll_page=UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500010');
SET @hll_read=UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500011');
SET @hll_write=UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500012');
SET @hll_sync=UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500013');
SET @hll_queue=UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500014');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES
(@hll_page,@hll_app,@hll_parent,'SUPPLY_CHAIN.PAGE.HUOLALA','PAGE',NULL,'货拉拉接入',28,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@hll_read,@hll_app,@hll_page,'SUPPLY_CHAIN.API.HUOLALA_READ','API','integration:huolala:read','查看货拉拉接入及员工关联',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@hll_write,@hll_app,@hll_page,'SUPPLY_CHAIN.API.HUOLALA_WRITE','API','integration:huolala:write','维护货拉拉授权及员工关联',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@hll_sync,@hll_app,@hll_page,'SUPPLY_CHAIN.API.HUOLALA_SYNC','API','integration:huolala:sync','同步货拉拉企业订单及员工',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(@hll_queue,@hll_app,@hll_page,'SUPPLY_CHAIN.API.HUOLALA_QUEUE','API','order:logistics-cost:manage-imports','处理本租户货拉拉待关联订单及账单差异',40,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,visible,keep_alive,created_at,updated_at)
VALUES(@hll_page,'supply.integration.huolala','/supply-chain/integration/huolala',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE visible=TRUE,route_path=VALUES(route_path),updated_at=UTC_TIMESTAMP(6);
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT p.package_version_id,r.id,UTC_TIMESTAMP(6) FROM iam_package_resource p
JOIN iam_resource r ON r.id IN (@hll_page,@hll_read,@hll_write,@hll_sync,@hll_queue)
WHERE p.resource_id=@hll_parent;
INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT rr.tenant_id,rr.id,r.id,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_role rr JOIN iam_tenant_subscription s ON s.tenant_id=rr.tenant_id
 AND s.status IN ('ACTIVE','SCHEDULED') AND s.deleted_at IS NULL AND s.effective_from<=UTC_TIMESTAMP(6) AND s.effective_to>UTC_TIMESTAMP(6)
JOIN iam_package_resource p ON p.package_version_id=s.package_version_id
JOIN iam_resource r ON r.id=p.resource_id AND r.id IN (@hll_page,@hll_read,@hll_write,@hll_sync,@hll_queue)
WHERE rr.role_type='SYSTEM' AND rr.role_code='TENANT_SUPER_ADMIN' AND rr.status='ACTIVE' AND rr.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@hll_page,n.id,@hll_page,'PAGE','货拉拉接入',28,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM iam_app_menu_node n WHERE n.resource_id=@hll_parent AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),visible=TRUE,status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);
UPDATE iam_tenant SET policy_version=policy_version+1,updated_at=UTC_TIMESTAMP(6) WHERE status='ACTIVE' AND deleted_at IS NULL;
