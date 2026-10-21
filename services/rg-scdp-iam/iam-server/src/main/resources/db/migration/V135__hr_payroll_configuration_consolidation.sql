-- Reparent existing resources without changing resource IDs or role/package grants.
SET @payroll_config=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL-CONFIG');
-- Some installations retain the original menu resource code. Locate it from the existing allowance page.
SET @payroll_config=(SELECT parent_id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.ALLOWANCE-RULES');
UPDATE iam_resource SET parent_id=@payroll_config,display_name='方案与适用人员',sort_order=25,updated_at=UTC_TIMESTAMP(6)
 WHERE resource_code='SUPPLY_CHAIN.PAGE.HR_PAYROLL_SCHEMES';
UPDATE iam_resource SET display_name='其他薪资标准',updated_at=UTC_TIMESTAMP(6)
 WHERE resource_code='SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.ALLOWANCE-RULES';
UPDATE iam_resource SET display_name='提成规则',updated_at=UTC_TIMESTAMP(6)
 WHERE resource_code='SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.COMMISSION-RULES';
UPDATE iam_resource SET display_name='绩效规则',updated_at=UTC_TIMESTAMP(6)
 WHERE resource_code='SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PERFORMANCE-RULES';
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
 SET n.display_name=r.display_name,n.sort_order=r.sort_order,n.updated_at=UTC_TIMESTAMP(6)
 WHERE r.resource_code IN ('SUPPLY_CHAIN.PAGE.HR_PAYROLL_SCHEMES','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.ALLOWANCE-RULES',
 'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.COMMISSION-RULES','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PERFORMANCE-RULES') AND n.deleted_at IS NULL;
UPDATE iam_app_menu_node n JOIN iam_app_menu_node p ON p.tenant_id=n.tenant_id AND p.application_id=n.application_id
 AND p.resource_id=@payroll_config AND p.deleted_at IS NULL
 JOIN iam_resource r ON r.id=n.resource_id AND r.resource_code='SUPPLY_CHAIN.PAGE.HR_PAYROLL_SCHEMES'
 SET n.parent_id=p.id,n.updated_at=UTC_TIMESTAMP(6) WHERE n.deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE application_id=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE id IN (SELECT DISTINCT tenant_id FROM iam_app_menu_node WHERE resource_id=@payroll_config AND deleted_at IS NULL);
