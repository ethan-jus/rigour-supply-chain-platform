-- IAM V68：恢复 CRM 客户地址、客户类型、归属地区入口。
--
-- 后端已存在客户地址簿、客户类型、客户归属地区查询契约；本迁移只恢复这些已接入页面，
-- 不恢复门店档案、客户360、外部员工或信用政策等仍未接入业务页的旧入口。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

SET @crm_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_customer_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000200');
SET @crm_customer_type_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000202');
SET @crm_customer_area_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000292');
SET @crm_shipping_address_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000294');

UPDATE iam_resource
   SET display_name = 'CRM',
       sort_order = 30,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_root_menu;

UPDATE iam_resource
   SET parent_id = @crm_root_menu,
       display_name = '客户管理',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customers_menu;

UPDATE iam_resource
   SET parent_id = @crm_customers_menu,
       display_name = '客户档案',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customer_page;

UPDATE iam_resource
   SET parent_id = @crm_customers_menu,
       display_name = '客户地址',
       sort_order = 20,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_shipping_address_page;

UPDATE iam_resource
   SET parent_id = @crm_customers_menu,
       display_name = '客户类型',
       sort_order = 30,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customer_type_page;

UPDATE iam_resource
   SET parent_id = @crm_customers_menu,
       display_name = '归属地区',
       sort_order = 40,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customer_area_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.menu',
       route_path = NULL,
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customers_menu;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.profiles',
       route_path = '/supply-chain/crm/customers/profiles',
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.shipping-addresses',
       route_path = '/supply-chain/crm/customers/shipping-addresses',
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_shipping_address_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.levels-tags',
       route_path = '/supply-chain/crm/customers/levels-tags',
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_type_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.areas',
       route_path = '/supply-chain/crm/customers/areas',
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_area_page;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, crm_resource.resource_id, @changed_at, NULL
  FROM (
    SELECT @crm_customers_menu AS resource_id
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @crm_shipping_address_page
    UNION ALL SELECT @crm_customer_type_page
    UNION ALL SELECT @crm_customer_area_page
  ) crm_resource
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, crm_resource.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @crm_customers_menu AS resource_id
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @crm_shipping_address_page
    UNION ALL SELECT @crm_customer_type_page
    UNION ALL SELECT @crm_customer_area_page
  ) crm_resource
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, crm_resource.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @crm_customers_menu AS resource_id
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @crm_shipping_address_page
    UNION ALL SELECT @crm_customer_type_page
    UNION ALL SELECT @crm_customer_area_page
  ) crm_resource
 WHERE existing_grant.resource_id = @crm_customer_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, crm_resource.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @crm_customers_menu AS resource_id
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @crm_shipping_address_page
    UNION ALL SELECT @crm_customer_type_page
    UNION ALL SELECT @crm_customer_area_page
  ) crm_resource
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_tenant_menu_config menu_config
        WHERE menu_config.tenant_id = tenant_record.id
          AND menu_config.resource_id IN (
              @crm_customers_menu,
              @crm_customer_page,
              @crm_shipping_address_page,
              @crm_customer_type_page,
              @crm_customer_area_page
          )
          AND menu_config.visible = 1
   );
