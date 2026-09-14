-- The live supply-chain sync center calls these two human-facing DHB APIs.
-- Keep resource IDs and existing V9 package/ordinary-role grants; do not revive the retired app/UI.
SET @changed_at = UTC_TIMESTAMP(6);
SET @supply_application = (
    SELECT id FROM iam_application
     WHERE app_code = 'SUPPLY_CHAIN' AND app_scope = 'TENANT'
       AND status = 'ACTIVE' AND deleted_at IS NULL
);
SET @sync_center_page = (
    SELECT id FROM iam_resource
     WHERE resource_code = 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_OVERVIEW'
       AND application_id = @supply_application AND resource_type = 'PAGE'
       AND status = 'ACTIVE' AND deleted_at IS NULL
);

UPDATE iam_resource
   SET application_id = @supply_application, parent_id = @sync_center_page,
       updated_at = @changed_at, version = version + 1
 WHERE ((resource_code = 'DHB_INTEGRATION.API.READ' AND permission_code = 'integration:dhb:read')
        OR (resource_code = 'DHB_INTEGRATION.API.WRITE' AND permission_code = 'integration:dhb:write'))
   AND resource_type = 'API' AND status = 'ACTIVE' AND deleted_at IS NULL
   AND @supply_application IS NOT NULL AND @sync_center_page IS NOT NULL
   AND (application_id <> @supply_application OR parent_id IS NULL OR parent_id <> @sync_center_page);

-- V9 already persisted both resources in the standard package. Moving the same IDs preserves it.
-- Do not grant either API to additional packages or add any SERVICE-only permission.
INSERT INTO iam_role_resource
    (tenant_id, role_id, resource_id, status, created_at, created_by, updated_at, updated_by)
SELECT DISTINCT role_record.tenant_id, role_record.id, resource_record.id,
       'ACTIVE', @changed_at, NULL, @changed_at, NULL
  FROM iam_role role_record
  JOIN iam_tenant tenant_record ON tenant_record.id = role_record.tenant_id
   AND tenant_record.status = 'ACTIVE' AND tenant_record.deleted_at IS NULL
  JOIN iam_tenant_subscription subscription ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED') AND subscription.deleted_at IS NULL
   AND subscription.effective_from <= @changed_at AND subscription.effective_to > @changed_at
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record ON resource_record.id = package_resource.resource_id
   AND resource_record.application_id = @supply_application
   AND resource_record.parent_id = @sync_center_page
   AND resource_record.resource_type = 'API'
   AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
 WHERE role_record.role_type = 'SYSTEM' AND role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
   AND ((resource_record.resource_code = 'DHB_INTEGRATION.API.READ'
         AND resource_record.permission_code = 'integration:dhb:read')
        OR (resource_record.resource_code = 'DHB_INTEGRATION.API.WRITE'
            AND resource_record.permission_code = 'integration:dhb:write'))
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

-- Correct four existing human-facing labels; permission codes, scopes and grants do not change.
UPDATE iam_resource SET display_name = '查询订单、发货与收退款', updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.ORDER_READ' AND permission_code = 'order:read'
   AND application_id = @supply_application AND status = 'ACTIVE' AND deleted_at IS NULL
   AND display_name <> '查询订单、发货与收退款';
UPDATE iam_resource SET display_name = '维护商品、分类、品牌与规格', updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.ERP_PRODUCT_WRITE' AND permission_code = 'erp:product:write'
   AND application_id = @supply_application AND status = 'ACTIVE' AND deleted_at IS NULL
   AND display_name <> '维护商品、分类、品牌与规格';
UPDATE iam_resource SET display_name = '维护采购、库存与供应商', updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.ERP_SUPPLY_WRITE' AND permission_code = 'erp:supply:write'
   AND application_id = @supply_application AND status = 'ACTIVE' AND deleted_at IS NULL
   AND display_name <> '维护采购、库存与供应商';
UPDATE iam_resource SET display_name = '维护客户与客户基础数据', updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.CRM_CUSTOMER_WRITE' AND permission_code = 'crm:customer:write'
   AND application_id = @supply_application AND status = 'ACTIVE' AND deleted_at IS NULL
   AND display_name <> '维护客户与客户基础数据';
