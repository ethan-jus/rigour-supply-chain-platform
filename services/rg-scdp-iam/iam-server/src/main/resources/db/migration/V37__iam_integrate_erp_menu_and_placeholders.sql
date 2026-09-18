-- IAM V37：按 ERP Portal 菜单方案统一展示已实现页面与接口预占页面。
-- 本迁移只调整菜单层级、展示名称和可见性，不新增 ERP 业务处理，也不生成模拟数据。

SET @changed_at = TIMESTAMP('2026-08-11 17:30:00.000000');
SET @erp_master_data_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_attributes_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000285');

-- 商品/SPU、SKU、分类、品牌和规格统一挂在“商品与主数据”下；保留已有 route_key，避免 Portal 深链接失效。
UPDATE iam_resource
   SET display_name = '商品与主数据', updated_at = @changed_at
 WHERE id = @erp_master_data_menu;

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '商品/SPU', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, status = 'ACTIVE', display_name = 'SKU', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000169');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '分类', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000170');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '品牌', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000171');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '规格与包装', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '商品标签', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '数据同步', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');

-- 商品属性是旧的中间分组，保留资源以兼容历史授权，但不再作为 Portal 菜单节点展示。
UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id = @erp_attributes_menu;

-- 供应商、采购、仓库、库存和成本结算使用截图中的业务名称。
UPDATE iam_resource SET display_name = '供应商', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000173');
UPDATE iam_resource SET display_name = '采购订单', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000179');
UPDATE iam_resource SET display_name = '到货与入库', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000180');
UPDATE iam_resource SET display_name = '仓库作业', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000182');
UPDATE iam_resource SET display_name = '仓库与库位', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
UPDATE iam_resource SET display_name = '入库作业', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
UPDATE iam_resource SET display_name = '库存总览', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');

-- 已接入 ERP 本地查询/同步的页面，以及暂未接入接口但需要提前占位的页面。
UPDATE iam_resource_ui
   SET visible = 1, updated_at = @changed_at
 WHERE resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000168'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000169'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000170'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000171'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000172'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000284'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000286')
 );

-- 同步既有租户配置；新租户由订阅初始化流程按 iam_resource_ui.visible 获取默认值。
UPDATE iam_tenant_menu_config config
JOIN iam_resource_ui ui ON ui.resource_id = config.resource_id
   SET config.visible = ui.visible, config.updated_at = @changed_at
 WHERE config.resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000168'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000169'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000170'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000171'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000172'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),
    @erp_attributes_menu,
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000284'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000286')
 );

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
