-- IAM V34：将 ERP 商品中心收口为商品档案、商品属性和数据同步三个子入口。
-- SKU 不再作为独立菜单页，商品档案详情通过 ERP SKU 查询接口展示 SKU。

SET @seed_at = TIMESTAMP('2026-08-10 09:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @erp_master_data_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');
SET @erp_sku_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000169');
SET @erp_attributes_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000285');
SET @erp_sync_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@erp_attributes_menu, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.MENU.ERP_MASTER_DATA_ATTRIBUTES', 'MENU', NULL,
     '商品属性', 20, 'ACTIVE', @seed_at, @seed_at),
    (@erp_sync_page, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_SYNC', 'PAGE', NULL,
     '数据同步', 30, 'ACTIVE', @seed_at, @seed_at);

UPDATE iam_resource
   SET parent_id = @erp_attributes_menu, display_name = '商品分类', updated_at = @seed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000170');

UPDATE iam_resource
   SET parent_id = @erp_attributes_menu, display_name = '商品品牌', updated_at = @seed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000171');

UPDATE iam_resource
   SET parent_id = @erp_attributes_menu, display_name = '商品规格', updated_at = @seed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');

UPDATE iam_resource
   SET parent_id = @erp_attributes_menu, display_name = '商品标签', updated_at = @seed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

UPDATE iam_resource
   SET status = 'DISABLED', updated_at = @seed_at
 WHERE id = @erp_sku_page;

UPDATE iam_resource_ui
   SET visible = 0, updated_at = @seed_at
 WHERE resource_id = @erp_sku_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.attributes.categories',
       route_path = '/supply-chain/erp/master-data/attributes/categories',
       updated_at = @seed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000170');

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.attributes.brands',
       route_path = '/supply-chain/erp/master-data/attributes/brands',
       updated_at = @seed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000171');

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.attributes.specifications',
       route_path = '/supply-chain/erp/master-data/attributes/specifications',
       updated_at = @seed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.attributes.tags',
       route_path = '/supply-chain/erp/master-data/attributes/tags',
       updated_at = @seed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@erp_attributes_menu, 'supply.erp.master-data.attributes.menu', NULL, NULL, 1, 0, @seed_at, @seed_at),
    (@erp_sync_page, 'supply.erp.master-data.sync',
     '/supply-chain/erp/master-data/sync', NULL, 1, 0, @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES
    (UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), @erp_attributes_menu, @seed_at, NULL),
    (UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), @erp_sync_page, @seed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @erp_attributes_menu AS resource_id
    UNION ALL SELECT @erp_sync_page
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
