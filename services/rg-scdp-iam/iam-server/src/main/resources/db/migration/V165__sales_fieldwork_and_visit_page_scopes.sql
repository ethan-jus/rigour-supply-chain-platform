-- 两个系统页面各自提供读取权限，角色沿用既有菜单授权和数据范围。
SET @sales_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
START TRANSACTION;
UPDATE iam_resource
SET permission_code=CASE resource_code
        WHEN 'SUPPLY_CHAIN.PAGE.SALES_DASHBOARD' THEN 'sales:fieldwork:read'
        WHEN 'SUPPLY_CHAIN.PAGE.SALES_VISITS_RECORDS' THEN 'sales:visit-record:read'
    END,
    updated_at=UTC_TIMESTAMP(6)
WHERE application_id=@sales_app AND resource_type='PAGE' AND deleted_at IS NULL
    AND resource_code IN ('SUPPLY_CHAIN.PAGE.SALES_DASHBOARD','SUPPLY_CHAIN.PAGE.SALES_VISITS_RECORDS');

UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id AND r.application_id=n.application_id
SET n.permission_code=NULL,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE n.application_id=@sales_app AND n.deleted_at IS NULL AND r.deleted_at IS NULL
    AND r.resource_code IN ('SUPPLY_CHAIN.PAGE.SALES_DASHBOARD','SUPPLY_CHAIN.PAGE.SALES_VISITS_RECORDS')
    AND n.permission_code IS NOT NULL;

UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@sales_app;
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE id IN (SELECT tenant_id FROM iam_app_settings WHERE application_id=@sales_app);
COMMIT;
