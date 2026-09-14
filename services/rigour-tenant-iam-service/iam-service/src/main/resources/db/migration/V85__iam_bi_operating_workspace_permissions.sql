-- Register explicit BI management capabilities; assigning them remains an IAM administration action.
SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @targets_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000396');
SET @operations_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000397');
SET @reconciliation_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000398');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@targets_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.BI_TARGETS_WRITE', 'API',
     'analytics:targets:write', '维护经营目标', 40, 'ACTIVE', @changed_at, @changed_at),
    (@operations_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.BI_OPERATIONS_WRITE', 'API',
     'analytics:operations:write', '维护运营跟进', 50, 'ACTIVE', @changed_at, @changed_at),
    (@reconciliation_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.BI_RECONCILIATION_WRITE', 'API',
     'analytics:reconciliation:write', '生成来源数据复核', 60, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resource_id, @changed_at, NULL FROM (
    SELECT @targets_write AS resource_id
    UNION ALL SELECT @operations_write
    UNION ALL SELECT @reconciliation_write
) resources
ON DUPLICATE KEY UPDATE created_at = created_at;
