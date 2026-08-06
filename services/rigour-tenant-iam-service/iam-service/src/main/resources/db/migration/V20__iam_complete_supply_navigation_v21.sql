-- IAM V20：按总体架构V2.1补齐供应链业务域二三级菜单，并将Portal飞书卡片收口到销售管理后台。
-- 缺失业务API不生成模拟数据；菜单、路由和权限挂载点先保持数据库动态配置。

SET @seed_at = TIMESTAMP('2026-08-06 20:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @erp = UUID_TO_BIN('019facf2-0000-7000-8000-000000000059');
SET @crm = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @orders = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_access = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @order_fulfillment = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @order_after_sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @city = UUID_TO_BIN('019facf2-0000-7000-8000-000000000051');
SET @bi = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @hr = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @channel = UUID_TO_BIN('019facf2-0000-7000-8000-000000000063');
SET @integration = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @settings = UUID_TO_BIN('019facf2-0000-7000-8000-000000000067');

-- 门户卡片不再建设独立PC飞书后台，统一进入供应链销售管理。
UPDATE iam_application
   SET app_type = 'INTERNAL',
       launch_mode = 'INTERNAL_ROUTE',
       target_uri = '/supply-chain/sales',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @app_feishu_sales
   AND app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_MASTER_DATA','MENU',NULL,'商品与主数据',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000168'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_PRODUCTS','PAGE',NULL,'商品/SPU',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000169'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_SKUS','PAGE',NULL,'SKU',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000170'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_CATEGORIES','PAGE',NULL,'分类',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000171'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_BRANDS','PAGE',NULL,'品牌',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000172'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_SPECIFICATIONS','PAGE',NULL,'规格与包装',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_SUPPLIERS','MENU',NULL,'供应商',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),'SUPPLY_CHAIN.PAGE.ERP_SUPPLIERS_PROFILES','PAGE',NULL,'供应商档案',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),'SUPPLY_CHAIN.PAGE.ERP_SUPPLIERS_PRODUCTS','PAGE',NULL,'供应商商品',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),'SUPPLY_CHAIN.PAGE.ERP_SUPPLIERS_PRICES','PAGE',NULL,'供应商价格',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_PROCUREMENT','MENU',NULL,'采购管理',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_REQUESTS','PAGE',NULL,'采购申请',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_ORDERS','PAGE',NULL,'采购订单',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_RECEIPTS','PAGE',NULL,'到货与入库',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_RETURNS','PAGE',NULL,'采购退货',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_WAREHOUSE','MENU',NULL,'仓库作业',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_LOCATIONS','PAGE',NULL,'仓库与库位',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_INBOUND','PAGE',NULL,'入库作业',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_OUTBOUND','PAGE',NULL,'出库作业',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_TRANSFERS','PAGE',NULL,'调拨作业',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_STOCKTAKING','PAGE',NULL,'盘点作业',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_INVENTORY','MENU',NULL,'库存管理',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_OVERVIEW','PAGE',NULL,'库存总览',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_AVAILABILITY','PAGE',NULL,'可用/锁定/在途库存',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_MOVEMENTS','PAGE',NULL,'库存流水',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_BATCHES','PAGE',NULL,'批次与效期',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_ALERTS','PAGE',NULL,'库存预警',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_COST_SETTLEMENT','MENU',NULL,'成本与采购结算',70,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'SUPPLY_CHAIN.PAGE.ERP_COST_SETTLEMENT_PURCHASE_PRICES','PAGE',NULL,'采购价格',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'SUPPLY_CHAIN.PAGE.ERP_COST_SETTLEMENT_RECEIPT_COSTS','PAGE',NULL,'入库成本',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'SUPPLY_CHAIN.PAGE.ERP_COST_SETTLEMENT_INVENTORY_COSTS','PAGE',NULL,'库存成本',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'SUPPLY_CHAIN.PAGE.ERP_COST_SETTLEMENT_PAYABLE_BASIS','PAGE',NULL,'供应商应付依据',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),@app_supply_chain,@crm,'SUPPLY_CHAIN.MENU.CRM_CUSTOMERS','MENU',NULL,'客户与商家',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000200'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_PROFILES','PAGE',NULL,'客户/商家档案',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000201'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_STORES','PAGE',NULL,'门店档案',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000202'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_LEVELS_TAGS','PAGE',NULL,'客户等级与标签',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000203'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_CUSTOMER_360','PAGE',NULL,'客户 360',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),@app_supply_chain,@crm,'SUPPLY_CHAIN.MENU.CRM_ASSIGNMENTS','MENU',NULL,'客户归属',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000205'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),'SUPPLY_CHAIN.PAGE.CRM_ASSIGNMENTS_SALES','PAGE',NULL,'销售归属',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000206'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),'SUPPLY_CHAIN.PAGE.CRM_ASSIGNMENTS_CITY_TEAMS','PAGE',NULL,'城市与团队归属',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000207'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),'SUPPLY_CHAIN.PAGE.CRM_ASSIGNMENTS_HISTORY','PAGE',NULL,'归属变更记录',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),@app_supply_chain,@crm,'SUPPLY_CHAIN.MENU.CRM_CREDIT_POLICY','MENU',NULL,'信用与结算政策',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000209'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_LIMITS','PAGE',NULL,'信用额度',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000210'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_TERMS','PAGE',NULL,'账期与结算周期',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000211'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_PAYMENT_METHODS','PAGE',NULL,'付款方式',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000212'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_INVOICING','PAGE',NULL,'开票资料',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000213'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_APPROVALS','PAGE',NULL,'政策审批记录',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),@app_supply_chain,@city,'SUPPLY_CHAIN.MENU.CITY_SCOPE','MENU',NULL,'城市与服务范围',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000215'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'SUPPLY_CHAIN.PAGE.CITY_SCOPE_PROFILES','PAGE',NULL,'城市运营档案',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000216'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'SUPPLY_CHAIN.PAGE.CITY_SCOPE_SERVICE_AREAS','PAGE',NULL,'服务区域',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000217'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'SUPPLY_CHAIN.PAGE.CITY_SCOPE_FULFILLMENT_NODES','PAGE',NULL,'履约节点',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000218'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'SUPPLY_CHAIN.PAGE.CITY_SCOPE_OWNERS','PAGE',NULL,'城市责任人',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),@app_supply_chain,@city,'SUPPLY_CHAIN.MENU.CITY_TASKS','MENU',NULL,'运营任务',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000220'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'SUPPLY_CHAIN.PAGE.CITY_TASKS_TODOS','PAGE',NULL,'城市待办',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000221'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'SUPPLY_CHAIN.PAGE.CITY_TASKS_FULFILLMENT_EXCEPTIONS','PAGE',NULL,'履约异常',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000222'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'SUPPLY_CHAIN.PAGE.CITY_TASKS_CUSTOMER_EXCEPTIONS','PAGE',NULL,'客户经营异常',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000223'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'SUPPLY_CHAIN.PAGE.CITY_TASKS_ACTIVITIES','PAGE',NULL,'城市活动与复盘',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),@app_supply_chain,@city,'SUPPLY_CHAIN.MENU.CITY_CONFIGURATION','MENU',NULL,'城市配置',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000225'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),'SUPPLY_CHAIN.PAGE.CITY_CONFIGURATION_TARGETS','PAGE',NULL,'城市目标',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000226'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),'SUPPLY_CHAIN.PAGE.CITY_CONFIGURATION_BUDGETS','PAGE',NULL,'预算与成本配置',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000227'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),'SUPPLY_CHAIN.PAGE.CITY_CONFIGURATION_PARTNERS','PAGE',NULL,'合作方配置',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000228'),@app_supply_chain,@order_access,'SUPPLY_CHAIN.PAGE.ORDER_ACCESS_BACKSTAGE','PAGE',NULL,'后台代客下单',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000229'),@app_supply_chain,@order_access,'SUPPLY_CHAIN.PAGE.ORDER_ACCESS_EXCEPTION','PAGE',NULL,'来源异常',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000230'),@app_supply_chain,@order_fulfillment,'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_OWNERSHIP','PAGE',NULL,'履约归属',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000231'),@app_supply_chain,@order_fulfillment,'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_INVENTORY','PAGE',NULL,'库存与仓库协同',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000232'),@app_supply_chain,@order_fulfillment,'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_EXCEPTION','PAGE',NULL,'履约异常',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000233'),@app_supply_chain,@order_after_sales,'SUPPLY_CHAIN.PAGE.ORDER_AFTER_SALES_EXCHANGE','PAGE',NULL,'换货与补发',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000234'),@app_supply_chain,@order_after_sales,'SUPPLY_CHAIN.PAGE.ORDER_AFTER_SALES_APPROVAL','PAGE',NULL,'售后审批',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),@app_supply_chain,@orders,'SUPPLY_CHAIN.MENU.ORDER_SETTLEMENT','MENU',NULL,'订单结算事实',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000236'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIVABLE','PAGE',NULL,'应收依据',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000237'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS','PAGE',NULL,'回款状态',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000238'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION','PAGE',NULL,'对账差异',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000239'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_ORDERS_FULFILLMENT','PAGE',NULL,'订单与履约',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000240'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_SALES','PAGE',NULL,'销售经营',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000241'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_CITIES','PAGE',NULL,'城市经营',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000242'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_CUSTOMERS_STORES','PAGE',NULL,'客户与门店',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000243'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_PRODUCTS_PROCUREMENT_INVENTORY','PAGE',NULL,'商品、采购与库存',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000244'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_FINANCE_COLLECTIONS','PAGE',NULL,'财务与回款',70,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000245'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_SYNC_QUALITY','PAGE',NULL,'数据同步质量',80,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000246'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_METRICS','PAGE',NULL,'指标中心',90,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000247'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_DASHBOARDS','PAGE',NULL,'看板管理',100,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000248'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_ASSIGNMENTS','PAGE',NULL,'任职与调动',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000249'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_CALENDAR_POLICIES','PAGE',NULL,'工作日历与考勤政策',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000250'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_ATTENDANCE_APPEALS','PAGE',NULL,'正式考勤与申诉',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000251'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_PAYROLL_COMMISSION','PAGE',NULL,'薪酬与提成',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000252'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_PERFORMANCE','PAGE',NULL,'绩效核算',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000253'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_MONTHLY_CLOSE','PAGE',NULL,'月结与冲回',70,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000254'),@app_supply_chain,@channel,'SUPPLY_CHAIN.PAGE.CHANNEL_RELATIONSHIPS','PAGE',NULL,'代理关系树',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000255'),@app_supply_chain,@channel,'SUPPLY_CHAIN.PAGE.CHANNEL_LEVELS','PAGE',NULL,'代理等级',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000256'),@app_supply_chain,@channel,'SUPPLY_CHAIN.PAGE.CHANNEL_QUOTAS','PAGE',NULL,'额度与占用',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000257'),@app_supply_chain,@channel,'SUPPLY_CHAIN.PAGE.CHANNEL_APPROVALS','PAGE',NULL,'审批与释放',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000258'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_PRODUCT_INVENTORY','PAGE',NULL,'商品与库存参数',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000259'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_PROCUREMENT','PAGE',NULL,'采购规则',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000260'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_CUSTOMER_LEVELS_TAGS','PAGE',NULL,'客户等级与标签',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000261'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_CREDIT_POLICY_TEMPLATES','PAGE',NULL,'信用与结算政策模板',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000262'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_ORDER_AFTER_SALES','PAGE',NULL,'订单和售后规则',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000263'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_FULFILLMENT_ALLOCATION','PAGE',NULL,'履约分配规则',70,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000264'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_CITY_SERVICE_SCOPE','PAGE',NULL,'城市服务范围',80,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000265'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_NUMBERING_DICTIONARIES','PAGE',NULL,'业务编号与领域字典',90,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000266'),@app_supply_chain,@integration,'SUPPLY_CHAIN.PAGE.INTEGRATION_SYNC_BATCHES','PAGE',NULL,'同步批次与游标',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000267'),@app_supply_chain,@integration,'SUPPLY_CHAIN.PAGE.INTEGRATION_EXTERNAL_ID_MAPPINGS','PAGE',NULL,'外部 ID 映射',80,'ACTIVE',@seed_at,@seed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'supply.erp.master-data.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000168'),'supply.erp.master-data.products','/supply-chain/erp/master-data/products',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000169'),'supply.erp.master-data.skus','/supply-chain/erp/master-data/skus',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000170'),'supply.erp.master-data.categories','/supply-chain/erp/master-data/categories',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000171'),'supply.erp.master-data.brands','/supply-chain/erp/master-data/brands',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000172'),'supply.erp.master-data.specifications','/supply-chain/erp/master-data/specifications',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),'supply.erp.suppliers.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),'supply.erp.suppliers.profiles','/supply-chain/erp/suppliers/profiles',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),'supply.erp.suppliers.products','/supply-chain/erp/suppliers/products',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),'supply.erp.suppliers.prices','/supply-chain/erp/suppliers/prices',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'supply.erp.procurement.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),'supply.erp.procurement.requests','/supply-chain/erp/procurement/requests',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),'supply.erp.procurement.orders','/supply-chain/erp/procurement/orders',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),'supply.erp.procurement.receipts','/supply-chain/erp/procurement/receipts',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),'supply.erp.procurement.returns','/supply-chain/erp/procurement/returns',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'supply.erp.warehouse.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),'supply.erp.warehouse.locations','/supply-chain/erp/warehouse/locations',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),'supply.erp.warehouse.inbound','/supply-chain/erp/warehouse/inbound',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),'supply.erp.warehouse.outbound','/supply-chain/erp/warehouse/outbound',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),'supply.erp.warehouse.transfers','/supply-chain/erp/warehouse/transfers',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),'supply.erp.warehouse.stocktaking','/supply-chain/erp/warehouse/stocktaking',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'supply.erp.inventory.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),'supply.erp.inventory.overview','/supply-chain/erp/inventory/overview',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),'supply.erp.inventory.availability','/supply-chain/erp/inventory/availability',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),'supply.erp.inventory.movements','/supply-chain/erp/inventory/movements',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),'supply.erp.inventory.batches','/supply-chain/erp/inventory/batches',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),'supply.erp.inventory.alerts','/supply-chain/erp/inventory/alerts',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'supply.erp.cost-settlement.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),'supply.erp.cost-settlement.purchase-prices','/supply-chain/erp/cost-settlement/purchase-prices',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),'supply.erp.cost-settlement.receipt-costs','/supply-chain/erp/cost-settlement/receipt-costs',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),'supply.erp.cost-settlement.inventory-costs','/supply-chain/erp/cost-settlement/inventory-costs',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),'supply.erp.cost-settlement.payable-basis','/supply-chain/erp/cost-settlement/payable-basis',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'supply.crm.customers.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000200'),'supply.crm.customers.profiles','/supply-chain/crm/customers/profiles',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000201'),'supply.crm.customers.stores','/supply-chain/crm/customers/stores',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000202'),'supply.crm.customers.levels-tags','/supply-chain/crm/customers/levels-tags',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000203'),'supply.crm.customers.customer-360','/supply-chain/crm/customers/customer-360',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),'supply.crm.assignments.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000205'),'supply.crm.assignments.sales','/supply-chain/crm/assignments/sales',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000206'),'supply.crm.assignments.city-teams','/supply-chain/crm/assignments/city-teams',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000207'),'supply.crm.assignments.history','/supply-chain/crm/assignments/history',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'supply.crm.credit-policy.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000209'),'supply.crm.credit-policy.limits','/supply-chain/crm/credit-policy/limits',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000210'),'supply.crm.credit-policy.terms','/supply-chain/crm/credit-policy/terms',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000211'),'supply.crm.credit-policy.payment-methods','/supply-chain/crm/credit-policy/payment-methods',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000212'),'supply.crm.credit-policy.invoicing','/supply-chain/crm/credit-policy/invoicing',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000213'),'supply.crm.credit-policy.approvals','/supply-chain/crm/credit-policy/approvals',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'supply.city.scope.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000215'),'supply.city.scope.profiles','/supply-chain/city/scope/profiles',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000216'),'supply.city.scope.service-areas','/supply-chain/city/scope/service-areas',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000217'),'supply.city.scope.fulfillment-nodes','/supply-chain/city/scope/fulfillment-nodes',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000218'),'supply.city.scope.owners','/supply-chain/city/scope/owners',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'supply.city.tasks.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000220'),'supply.city.tasks.todos','/supply-chain/city/tasks/todos',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000221'),'supply.city.tasks.fulfillment-exceptions','/supply-chain/city/tasks/fulfillment-exceptions',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000222'),'supply.city.tasks.customer-exceptions','/supply-chain/city/tasks/customer-exceptions',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000223'),'supply.city.tasks.activities','/supply-chain/city/tasks/activities',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),'supply.city.configuration.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000225'),'supply.city.configuration.targets','/supply-chain/city/configuration/targets',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000226'),'supply.city.configuration.budgets','/supply-chain/city/configuration/budgets',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000227'),'supply.city.configuration.partners','/supply-chain/city/configuration/partners',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000228'),'supply.order.access.backstage','/supply-chain/order/access/backstage',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000229'),'supply.order.access.exceptions','/supply-chain/order/access/exceptions',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000230'),'supply.order.fulfillment.ownership','/supply-chain/order/fulfillment/ownership',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000231'),'supply.order.fulfillment.inventory','/supply-chain/order/fulfillment/inventory',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000232'),'supply.order.fulfillment.exceptions','/supply-chain/order/fulfillment/exceptions',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000233'),'supply.order.after-sales.exchanges','/supply-chain/order/after-sales/exchanges',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000234'),'supply.order.after-sales.approvals','/supply-chain/order/after-sales/approvals',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),'supply.order.settlement.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000236'),'supply.order.settlement.receivable','/supply-chain/order/settlement/receivable',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000237'),'supply.order.settlement.collections','/supply-chain/order/settlement/collections',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000238'),'supply.order.settlement.reconciliation','/supply-chain/order/settlement/reconciliation',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000239'),'supply.bi.orders-fulfillment','/supply-chain/bi/orders-fulfillment',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000240'),'supply.bi.sales','/supply-chain/bi/sales',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000241'),'supply.bi.cities','/supply-chain/bi/cities',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000242'),'supply.bi.customers-stores','/supply-chain/bi/customers-stores',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000243'),'supply.bi.products-procurement-inventory','/supply-chain/bi/products-procurement-inventory',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000244'),'supply.bi.finance-collections','/supply-chain/bi/finance-collections',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000245'),'supply.bi.sync-quality','/supply-chain/bi/sync-quality',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000246'),'supply.bi.metrics','/supply-chain/bi/metrics',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000247'),'supply.bi.dashboards','/supply-chain/bi/dashboards',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000248'),'supply.hr.assignments','/supply-chain/hr/assignments',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000249'),'supply.hr.calendar-policies','/supply-chain/hr/calendar-policies',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000250'),'supply.hr.attendance-appeals','/supply-chain/hr/attendance-appeals',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000251'),'supply.hr.payroll-commission','/supply-chain/hr/payroll-commission',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000252'),'supply.hr.performance','/supply-chain/hr/performance',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000253'),'supply.hr.monthly-close','/supply-chain/hr/monthly-close',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000254'),'supply.channel.relationships','/supply-chain/channel/relationships',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000255'),'supply.channel.levels','/supply-chain/channel/levels',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000256'),'supply.channel.quotas','/supply-chain/channel/quotas',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000257'),'supply.channel.approvals','/supply-chain/channel/approvals',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000258'),'supply.settings.product-inventory','/supply-chain/settings/product-inventory',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000259'),'supply.settings.procurement','/supply-chain/settings/procurement',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000260'),'supply.settings.customer-levels-tags','/supply-chain/settings/customer-levels-tags',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000261'),'supply.settings.credit-policy-templates','/supply-chain/settings/credit-policy-templates',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000262'),'supply.settings.order-after-sales','/supply-chain/settings/order-after-sales',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000263'),'supply.settings.fulfillment-allocation','/supply-chain/settings/fulfillment-allocation',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000264'),'supply.settings.city-service-scope','/supply-chain/settings/city-service-scope',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000265'),'supply.settings.numbering-dictionaries','/supply-chain/settings/numbering-dictionaries',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000266'),'supply.integration.sync-batches','/supply-chain/integration/sync-batches',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000267'),'supply.integration.external-id-mappings','/supply-chain/integration/external-id-mappings',NULL,1,0,@seed_at,@seed_at);

-- 外部集成正式使用独立integration pathroot，不再把供应商名称暴露为技术路由边界。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 10,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.overview',
       ui_record.route_path = '/supply-chain/integration',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 20,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.connections',
       ui_record.route_path = '/supply-chain/integration/connections',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000088');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 30,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.sync-tasks',
       ui_record.route_path = '/supply-chain/integration/sync-tasks',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000086');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 50,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.field-mappings',
       ui_record.route_path = '/supply-chain/integration/field-mappings',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000089');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 60,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.retries',
       ui_record.route_path = '/supply-chain/integration/retries-dead-letters',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000087');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 70,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.reconciliation',
       ui_record.route_path = '/supply-chain/integration/reconciliation',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000090');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 90,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.raw-data',
       ui_record.route_path = '/supply-chain/integration/raw-data',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000085');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 100,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.sovereignty',
       ui_record.route_path = '/supply-chain/integration/sovereignty',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000091');

-- 新菜单进入现行标准套餐并授予既有租户超级管理员；普通角色仍由菜单管理按需配置。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_record.id, @seed_at, NULL
  FROM iam_resource resource_record
 WHERE resource_record.id BETWEEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000167') AND UUID_TO_BIN('019facf2-0000-7000-8000-000000000267')
ON DUPLICATE KEY UPDATE created_at = @seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resource_record.id, 'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN iam_resource resource_record
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
   AND resource_record.id BETWEEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000167') AND UUID_TO_BIN('019facf2-0000-7000-8000-000000000267')
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
