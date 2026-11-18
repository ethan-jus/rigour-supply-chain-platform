-- The existing review entries now handle salesperson appeals only.
UPDATE iam_resource SET display_name='拜访申诉',updated_at=UTC_TIMESTAMP(6)
WHERE resource_code IN ('SUPPLY_CHAIN.PAGE.SALES_VISIT_REVIEW','SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_REVIEW')
  AND deleted_at IS NULL;

UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
SET n.display_name=r.display_name,n.updated_at=UTC_TIMESTAMP(6)
WHERE r.resource_code IN ('SUPPLY_CHAIN.PAGE.SALES_VISIT_REVIEW','SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_REVIEW')
  AND n.deleted_at IS NULL AND r.deleted_at IS NULL;

UPDATE iam_app_settings s SET s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6)
WHERE EXISTS(SELECT 1 FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
  WHERE n.tenant_id=s.tenant_id AND n.application_id=s.application_id AND n.deleted_at IS NULL
  AND r.resource_code IN ('SUPPLY_CHAIN.PAGE.SALES_VISIT_REVIEW','SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_REVIEW'));
