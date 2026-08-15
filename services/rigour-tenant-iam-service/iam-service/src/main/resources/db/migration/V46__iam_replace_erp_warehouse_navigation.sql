-- IAM V46：移除 ERP“仓库作业”菜单，将“库存管理”改为“仓库管理”，并统一挂载仓库业务子页。
-- 只复用 V20 已存在的资源 ID，避免租户授权丢失；页面是否可用仍由 Portal 的实际接口映射决定。

SET @changed_at = TIMESTAMP('2026-08-15 12:00:00.000000');
SET @erp_warehouse_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000182');
SET @erp_inventory_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000188');

-- “仓库作业”及其旧菜单节点从动态导航中移除；资源保留以兼容历史授权记录。
UPDATE iam_resource
   SET status = 'DISABLED', updated_at = @changed_at
 WHERE id = @erp_warehouse_menu;

UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id = @erp_warehouse_menu;

-- 复用原仓库/库存资源作为新“仓库管理”八个子页，保持既有套餐和角色授权关系。
UPDATE iam_resource
   SET display_name = '仓库管理', updated_at = @changed_at
 WHERE id = @erp_inventory_menu;

UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_WAREHOUSES',
       display_name = '仓库信息', sort_order = 80, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_INBOUND',
       display_name = '入库单', sort_order = 30, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_OUTBOUND',
       display_name = '出库单', sort_order = 40, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000185');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_TRANSFERS',
       display_name = '库存调拨', sort_order = 60, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000186');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_STOCKTAKING',
       display_name = '库存盘点', sort_order = 70, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000187');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_DASHBOARD',
       display_name = '库存看板', sort_order = 10, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_BALANCES',
       display_name = '库存', sort_order = 20, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000190');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_MOVEMENTS',
       display_name = '出入库流水', sort_order = 50, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000191');

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.warehouses',
       route_path = '/supply-chain/erp/inventory/warehouses', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.inbound',
       route_path = '/supply-chain/erp/inventory/inbound', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.outbound',
       route_path = '/supply-chain/erp/inventory/outbound', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000185');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.transfers',
       route_path = '/supply-chain/erp/inventory/transfers', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000186');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.stocktaking',
       route_path = '/supply-chain/erp/inventory/stocktaking', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000187');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.dashboard',
       route_path = '/supply-chain/erp/inventory/dashboard', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.inventory',
       route_path = '/supply-chain/erp/inventory/inventory', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000190');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.movements',
       route_path = '/supply-chain/erp/inventory/movements', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000191');

UPDATE iam_resource_ui
   SET visible = 1, updated_at = @changed_at
 WHERE resource_id = @erp_inventory_menu;

-- 立即同步既有租户的菜单覆盖；新租户沿用资源 UI 的默认值。
UPDATE iam_tenant_menu_config config
JOIN iam_resource_ui ui ON ui.resource_id = config.resource_id
   SET config.visible = ui.visible, config.updated_at = @changed_at
 WHERE config.resource_id IN (
    @erp_warehouse_menu, @erp_inventory_menu,
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000191')
 );

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
