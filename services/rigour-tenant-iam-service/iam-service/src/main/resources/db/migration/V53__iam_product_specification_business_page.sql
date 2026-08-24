-- IAM V53：新增 ERP 商品规格业务页。
--
-- 业务口径：
-- 1. “商品规格”属于 ERP 商品中心，用于维护我方商品多规格和子规格值。
-- 2. “订货宝规格档案”继续保留在外部同步，不进入 ERP 主流程菜单。

SET @changed_at = TIMESTAMP('2026-08-20 17:35:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @erp_product_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_product_specification_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000302');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @erp_product_specification_page,
    @app_supply_chain,
    @erp_product_menu,
    'SUPPLY_CHAIN.PAGE.ERP_PRODUCT_SPECIFICATIONS',
    'PAGE',
    NULL,
    '商品规格',
    50,
    'ACTIVE',
    @changed_at,
    @changed_at
) ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @erp_product_specification_page,
    'supply.erp.master-data.attributes.specifications',
    '/supply-chain/erp/master-data/attributes/specifications',
    NULL,
    1,
    0,
    @changed_at,
    @changed_at
) ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @erp_product_specification_page, @changed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @erp_product_specification_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.package_version_id = @standard_package_version
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;
