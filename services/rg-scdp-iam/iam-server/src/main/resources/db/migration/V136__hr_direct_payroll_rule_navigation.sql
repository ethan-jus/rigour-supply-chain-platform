-- Payroll now matches independent rules directly; remove the redundant scheme workflow.
UPDATE iam_resource_ui u JOIN iam_resource r ON r.id=u.resource_id
 SET u.visible=FALSE,u.updated_at=UTC_TIMESTAMP(6)
 WHERE r.resource_code='SUPPLY_CHAIN.PAGE.HR_PAYROLL_SCHEMES';
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
 SET n.visible=FALSE,n.updated_at=UTC_TIMESTAMP(6)
 WHERE r.resource_code IN ('SUPPLY_CHAIN.PAGE.HR_PAYROLL_SCHEMES','SUPPLY_CHAIN.API.HR_PAYROLL_SCHEME_PUBLISH') AND n.deleted_at IS NULL;
UPDATE iam_resource SET display_name='补贴规则',updated_at=UTC_TIMESTAMP(6)
 WHERE resource_code='SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.ALLOWANCE-RULES';
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
 SET n.display_name=r.display_name,n.updated_at=UTC_TIMESTAMP(6)
 WHERE r.resource_code='SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.ALLOWANCE-RULES' AND n.deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE application_id=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE id IN (SELECT DISTINCT n.tenant_id FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
 WHERE r.resource_code='SUPPLY_CHAIN.PAGE.HR_PAYROLL_SCHEMES' AND n.deleted_at IS NULL);
