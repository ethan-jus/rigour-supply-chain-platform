-- 同步中心定时任务菜单。复用 Integration 读写/HR同步及BI刷新权限，不额外扩权。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @sync_menu=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.integration.sync-control.menu');
SET @schedule=UUID_TO_BIN('6a3aeefe-4f56-4a96-a56b-60e2e3873d65');

INSERT INTO iam_resource(
    id,application_id,parent_id,resource_code,resource_type,permission_code,
    display_name,sort_order,status,created_at,updated_at)
VALUES(
    @schedule,@app,@sync_menu,'SUPPLY_CHAIN.PAGE.SYNC_SCHEDULE','PAGE',NULL,
    '定时任务',25,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    resource_type='PAGE',
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(
    resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(
    @schedule,'supply.integration.schedules','/supply-chain/integration/schedules',NULL,TRUE,FALSE,
    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    route_path=VALUES(route_path),
    visible=TRUE,
    updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@schedule,UTC_TIMESTAMP(6)
  FROM iam_package_resource
 WHERE resource_id=@sync_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@schedule,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource
 WHERE resource_id=@sync_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(
    tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
    sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@schedule,n.id,@schedule,'PAGE','定时任务',25,TRUE,
       'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n
 WHERE n.resource_id=@sync_menu
   AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    visible=TRUE,
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

UPDATE iam_tenant SET policy_version=policy_version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE status='ACTIVE' AND deleted_at IS NULL;
