-- IAM V19：按总体架构V2.1收口供应链一级业务域，并清理订货宝时期的临时菜单命名。
-- V18已在共享DEV执行，本迁移只演进运行时资源，不改写任何历史迁移。

SET @changed_at = TIMESTAMP('2026-08-06 19:00:00.000000');
SET @supply_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000049');
SET @dashboard = UUID_TO_BIN('019facf2-0000-7000-8000-000000000050');
SET @city = UUID_TO_BIN('019facf2-0000-7000-8000-000000000051');
SET @city_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000052');
SET @crm = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @crm_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000054');
SET @orders = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_access = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000057');
SET @erp = UUID_TO_BIN('019facf2-0000-7000-8000-000000000059');
SET @erp_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000060');
SET @hr = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @hr_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000062');
SET @channel = UUID_TO_BIN('019facf2-0000-7000-8000-000000000063');
SET @channel_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000064');
SET @bi = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @settings = UUID_TO_BIN('019facf2-0000-7000-8000-000000000067');
SET @settings_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000068');
SET @integration = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');

-- 一级业务域顺序严格对应V2.1 pathroot，不再使用“入口”等临时名称。
UPDATE iam_resource
   SET display_name = CASE id
       WHEN @dashboard THEN '供应链首页'
       WHEN @erp THEN 'ERP'
       WHEN @crm THEN 'CRM'
       WHEN @orders THEN '订单管理'
       WHEN @sales THEN '销售管理'
       WHEN @city THEN '城市运营'
       WHEN @bi THEN 'BI 数据看板'
       WHEN @hr THEN '人事与绩效'
       WHEN @channel THEN '渠道代理'
       WHEN @integration THEN '外部集成与数据同步'
       WHEN @settings THEN '业务设置'
       END,
       sort_order = CASE id
       WHEN @dashboard THEN 10 WHEN @erp THEN 20 WHEN @crm THEN 30
       WHEN @orders THEN 40 WHEN @sales THEN 50 WHEN @city THEN 60
       WHEN @bi THEN 70 WHEN @hr THEN 80 WHEN @channel THEN 90
       WHEN @integration THEN 100 WHEN @settings THEN 110 END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@dashboard,@erp,@crm,@orders,@sales,@city,@bi,@hr,@channel,@integration,@settings)
   AND parent_id = @supply_root;

UPDATE iam_resource_ui
   SET icon_key = CASE resource_id
       WHEN @dashboard THEN 'House'
       WHEN @erp THEN 'Goods'
       WHEN @crm THEN 'Shop'
       WHEN @orders THEN 'List'
       WHEN @sales THEN 'Location'
       WHEN @city THEN 'OfficeBuilding'
       WHEN @bi THEN 'TrendCharts'
       WHEN @hr THEN 'UserFilled'
       WHEN @channel THEN 'Avatar'
       WHEN @integration THEN 'Connection'
       WHEN @settings THEN 'Setting'
       END,
       updated_at = @changed_at
 WHERE resource_id IN (@dashboard,@erp,@crm,@orders,@sales,@city,@bi,@hr,@channel,@integration,@settings);

UPDATE iam_resource
   SET display_name = CASE id
       WHEN @city_home THEN '城市工作台'
       WHEN @crm_home THEN 'CRM 工作台'
       WHEN @erp_home THEN 'ERP 工作台'
       WHEN @hr_home THEN '员工档案'
       WHEN @channel_home THEN '代理档案'
       WHEN @bi_home THEN '经营驾驶舱'
       WHEN @settings_home THEN '业务设置概览'
       END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@city_home,@crm_home,@erp_home,@hr_home,@channel_home,@bi_home,@settings_home);

-- 将早期“订货宝菜单镜像”收口为订单域结构；保留稳定资源ID和直接路由兼容性。
SET @order_provider = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @order_stock_up = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @order_shipping = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @order_return = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @order_partner = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @order_fulfillment = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @order_after_sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @order_center = UUID_TO_BIN('019facf2-0000-7000-8000-000000000112');
SET @order_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000113');
SET @order_all = UUID_TO_BIN('019facf2-0000-7000-8000-000000000114');
SET @order_pending = UUID_TO_BIN('019facf2-0000-7000-8000-000000000115');
SET @order_exception = UUID_TO_BIN('019facf2-0000-7000-8000-000000000116');

UPDATE iam_resource
   SET display_name = '订单接入', sort_order = 20, version = version + 1, updated_at = @changed_at
 WHERE id = @order_access;

UPDATE iam_resource
   SET display_name = '订货宝订单', sort_order = 10, version = version + 1, updated_at = @changed_at
 WHERE id = @order_provider;

UPDATE iam_resource
   SET parent_id = @orders, resource_code = 'SUPPLY_CHAIN.MENU.ORDER_FULFILLMENT',
       resource_type = 'MENU', display_name = '履约编排', sort_order = 40,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_fulfillment;

UPDATE iam_resource
   SET parent_id = @order_fulfillment,
       display_name = CASE id WHEN @order_shipping THEN '发货与配送状态' ELSE '配送伙伴' END,
       sort_order = CASE id WHEN @order_shipping THEN 30 ELSE 40 END,
       version = version + 1, updated_at = @changed_at
 WHERE id IN (@order_shipping,@order_partner);

UPDATE iam_resource
   SET parent_id = @orders, resource_code = 'SUPPLY_CHAIN.MENU.ORDER_AFTER_SALES',
       resource_type = 'MENU', display_name = '售后管理', sort_order = 50,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_after_sales;

UPDATE iam_resource
   SET parent_id = @order_after_sales, display_name = '退货', sort_order = 10,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_return;

UPDATE iam_resource
   SET parent_id = @orders, resource_code = 'SUPPLY_CHAIN.MENU.ORDER_CENTER',
       resource_type = 'MENU', display_name = '订单中心', sort_order = 30,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_center;

UPDATE iam_resource
   SET parent_id = @orders, resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_DASHBOARD',
       resource_type = 'PAGE', display_name = '订单工作台', sort_order = 10,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_home;

UPDATE iam_resource
   SET parent_id = @order_center,
       resource_code = CASE id
           WHEN @order_all THEN 'SUPPLY_CHAIN.PAGE.ORDER_ALL'
           WHEN @order_pending THEN 'SUPPLY_CHAIN.PAGE.ORDER_PENDING'
           ELSE 'SUPPLY_CHAIN.PAGE.ORDER_EXCEPTION' END,
       resource_type = 'PAGE',
       display_name = CASE id
           WHEN @order_all THEN '全部订单'
           WHEN @order_pending THEN '待处理订单'
           ELSE '异常订单' END,
       sort_order = CASE id WHEN @order_all THEN 10 WHEN @order_pending THEN 20 ELSE 30 END,
       version = version + 1, updated_at = @changed_at
 WHERE id IN (@order_all,@order_pending,@order_exception);

UPDATE iam_resource_ui
   SET route_key = 'supply.order.fulfillment.menu', route_path = NULL,
       icon_key = NULL, visible = 1, updated_at = @changed_at
 WHERE resource_id = @order_fulfillment;
UPDATE iam_resource_ui
   SET route_key = 'supply.order.after-sales.menu', route_path = NULL,
       icon_key = NULL, visible = 1, updated_at = @changed_at
 WHERE resource_id = @order_after_sales;
UPDATE iam_resource_ui
   SET route_key = 'supply.order.center.menu', route_path = NULL,
       icon_key = NULL, visible = 1, updated_at = @changed_at
 WHERE resource_id = @order_center;
UPDATE iam_resource_ui
   SET route_key = 'supply.order.index', route_path = '/supply-chain/order',
       icon_key = 'House', visible = 1, updated_at = @changed_at
 WHERE resource_id = @order_home;
UPDATE iam_resource_ui
   SET route_key = CASE resource_id
           WHEN @order_all THEN 'supply.order.all'
           WHEN @order_pending THEN 'supply.order.pending'
           ELSE 'supply.order.exceptions' END,
       route_path = CASE resource_id
           WHEN @order_all THEN '/supply-chain/order/all'
           WHEN @order_pending THEN '/supply-chain/order/pending'
           ELSE '/supply-chain/order/exceptions' END,
       icon_key = NULL, visible = 1, updated_at = @changed_at
 WHERE resource_id IN (@order_all,@order_pending,@order_exception);

-- 仓库实际出库属于ERP；旧统计和订货宝后台镜像页保留直达兼容，但退出正式侧栏。
UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id IN (
    @order_stock_up,
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000107'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000108'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000109'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000110'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000117'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000118')
 );

-- 外部集成只保留供应链数据控制面，不再使用供应商名称作为一级业务域。
UPDATE iam_resource
   SET display_name = CASE id
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000084') THEN '集成工作台'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000085') THEN '原始数据查询'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000086') THEN '同步任务'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000087') THEN '重试与死信'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000088') THEN '订货宝连接'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000089') THEN '字段映射'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000090') THEN '数据对账'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000091') THEN '主权与切换状态'
       END,
       version = version + 1, updated_at = @changed_at
 WHERE id BETWEEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000084')
              AND UUID_TO_BIN('019facf2-0000-7000-8000-000000000091');

UPDATE iam_tenant tenant_record
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1 FROM iam_role role_record
        WHERE role_record.tenant_id = tenant_record.id
          AND role_record.role_code = 'TENANT_SUPER_ADMIN'
          AND role_record.status = 'ACTIVE'
          AND role_record.deleted_at IS NULL
   );
