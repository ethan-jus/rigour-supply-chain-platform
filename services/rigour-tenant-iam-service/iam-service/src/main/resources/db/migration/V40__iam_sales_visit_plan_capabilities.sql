-- IAM V40：主管维护拜访计划，销售本人读取自己的今日计划。

SET @seed_at = TIMESTAMP('2026-08-11 12:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @visit_plan_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000128');
SET @h5_plan_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028c');
SET @management_plan_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028d');
SET @management_plan_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028e');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_plan_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.VISIT_PLAN_READ', 'API', 'sales:visit-plan:own:read', '查询本人今日拜访计划', 130,
     'ACTIVE', @seed_at, @seed_at),
    (@management_plan_read, @app_supply_chain, @visit_plan_page,
     'SUPPLY_CHAIN.API.SALES_VISIT_PLAN_READ', 'API', 'sales:visit-plan:read', '查询拜访计划', 10,
     'ACTIVE', @seed_at, @seed_at),
    (@management_plan_write, @app_supply_chain, @visit_plan_page,
     'SUPPLY_CHAIN.API.SALES_VISIT_PLAN_WRITE', 'API', 'sales:visit-plan:write', '维护拜访计划', 20,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_plan_read AS resource_id
    UNION ALL SELECT @management_plan_read
    UNION ALL SELECT @management_plan_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id, source_grant.role_id, @h5_plan_read,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_resource source_resource
    ON source_resource.id=source_grant.resource_id
   AND source_resource.permission_code='sales:visit:own:read'
   AND source_resource.status='ACTIVE'
 WHERE source_grant.status='ACTIVE'
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id, source_grant.role_id, target_resource.id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_resource source_resource
    ON source_resource.id=source_grant.resource_id
   AND source_resource.permission_code='sales:visit:review'
   AND source_resource.status='ACTIVE'
 CROSS JOIN iam_resource target_resource
 WHERE source_grant.status='ACTIVE'
   AND target_resource.status='ACTIVE'
   AND target_resource.permission_code IN ('sales:visit-plan:read','sales:visit-plan:write')
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version=policy_version+1, version=version+1, updated_at=@seed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;
