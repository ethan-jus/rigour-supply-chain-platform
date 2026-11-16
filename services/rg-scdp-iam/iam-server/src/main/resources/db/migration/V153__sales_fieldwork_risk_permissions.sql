-- Fieldwork review is independent of CRM effectiveness; migrate existing review roles only.
SET @seed_at=UTC_TIMESTAMP(6);
SET @app=UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @parent=UUID_TO_BIN('019facf2-0000-7000-8000-000000000149');
SET @risk_read=UUID_TO_BIN('01a1cf05-0000-7000-8000-000000000001');
SET @risk_write=UUID_TO_BIN('01a1cf05-0000-7000-8000-000000000002');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES
(@risk_read,@app,@parent,'SUPPLY_CHAIN.API.SALES_RISK_READ','API','sales:risk:read','查看外勤核查事项',30,'ACTIVE',@seed_at,@seed_at),
(@risk_write,@app,@parent,'SUPPLY_CHAIN.API.SALES_RISK_WRITE','API','sales:risk:write','处理外勤核查事项',40,'ACTIVE',@seed_at,@seed_at);
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at,created_by)
SELECT DISTINCT old.package_version_id,added.id,@seed_at,NULL
FROM iam_package_resource old JOIN iam_resource source ON source.id=old.resource_id AND source.permission_code='sales:visit:review'
CROSS JOIN (SELECT @risk_read id UNION ALL SELECT @risk_write) added
WHERE 1=1
ON DUPLICATE KEY UPDATE created_at=iam_package_resource.created_at;
INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT DISTINCT old.tenant_id,old.role_id,added.id,'ACTIVE',@seed_at,@seed_at
FROM iam_role_resource old JOIN iam_resource source ON source.id=old.resource_id AND source.permission_code='sales:visit:review'
CROSS JOIN (SELECT @risk_read id UNION ALL SELECT @risk_write) added
WHERE old.status='ACTIVE' AND source.status='ACTIVE'
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=@seed_at;
UPDATE iam_tenant t SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=@seed_at
WHERE t.status='ACTIVE' AND t.deleted_at IS NULL AND EXISTS(
 SELECT 1 FROM iam_role_resource g WHERE g.tenant_id=t.id AND g.resource_id=@risk_read AND g.status='ACTIVE');
