-- IAM V33：ERP 商品主数据本地查询与订货宝同步权限。

SET @seed_at = TIMESTAMP('2026-08-10 07:30:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @erp_master_data_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');
SET @erp_sku_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000169');
SET @erp_product_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000282');
SET @erp_product_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000283');
SET @erp_product_tags_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

-- 一期菜单收口为商品中心的独立子页。
UPDATE iam_resource
   SET display_name = '商品中心', updated_at = @seed_at
 WHERE id = @erp_master_data_menu;

UPDATE iam_resource
   SET display_name = '商品档案', updated_at = @seed_at
 WHERE id = @erp_product_page;

UPDATE iam_resource
   SET display_name = '商品规格（SKU）', status = 'ACTIVE', updated_at = @seed_at
 WHERE id = @erp_sku_page;

UPDATE iam_resource_ui
   SET visible = 1, updated_at = @seed_at
 WHERE resource_id = @erp_sku_page;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@erp_product_read, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.API.ERP_PRODUCT_READ', 'API', 'erp:product:read',
     '查询ERP商品主数据', 10, 'ACTIVE', @seed_at, @seed_at),
    (@erp_product_write, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.API.ERP_PRODUCT_WRITE', 'API', 'erp:product:write',
     '同步订货宝商品主数据', 20, 'ACTIVE', @seed_at, @seed_at),
    (@erp_product_tags_page, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_TAGS', 'PAGE', NULL,
     '商品标签', 60, 'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @erp_product_tags_page, 'supply.erp.master-data.tags',
    '/supply-chain/erp/master-data/tags', NULL, 1, 0, @seed_at, @seed_at
);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @erp_product_read AS resource_id
    UNION ALL SELECT @erp_product_write
    UNION ALL SELECT @erp_product_tags_page
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @erp_product_read AS resource_id
    UNION ALL SELECT @erp_product_write
    UNION ALL SELECT @erp_product_tags_page
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
