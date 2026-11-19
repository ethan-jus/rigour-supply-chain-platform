-- A 版常驻分组。页签保留各自的资源、原链接和角色授权，新增页面沿用对应业务的可见范围。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
CREATE TEMPORARY TABLE scm_navigation_plan (
 depth INT NOT NULL, code VARCHAR(128) PRIMARY KEY, parent_code VARCHAR(128) NOT NULL, label VARCHAR(128) NOT NULL,
 ordinal INT NOT NULL, route_key VARCHAR(160), path VARCHAR(255), permission VARCHAR(128), source_code VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO scm_navigation_plan VALUES
 (1,'SUPPLY_CHAIN.SECTION.SALES.TEAM','SUPPLY_CHAIN.MENU.SALES','团队经营',10,'supply.section.sales.team',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.SALES_OVERVIEW','SUPPLY_CHAIN.SECTION.SALES.TEAM','销售总览',10,'supply.sales.overview','/supply-chain/sales/overview','analytics:dashboard:read','SUPPLY_CHAIN.PAGE.BI_SALES'),
 (2,'SUPPLY_CHAIN.PAGE.SALES_CUSTOMER_FOLLOWUPS','SUPPLY_CHAIN.SECTION.SALES.TEAM','客户跟进',20,'supply.sales.customer-followups','/supply-chain/sales/customer-followups','analytics:dashboard:read','SUPPLY_CHAIN.PAGE.BI_CUSTOMER'),
 (2,'SUPPLY_CHAIN.PAGE.SALES_RISK_CASES','SUPPLY_CHAIN.SECTION.SALES.TEAM','风险处置',30,'supply.sales.risk-cases','/supply-chain/sales/risk-cases','analytics:dashboard:read','SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK'),
 (1,'SUPPLY_CHAIN.MENU.SALES_VISIT','SUPPLY_CHAIN.MENU.SALES','拜访管理',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.SALES_VISIT_PLAN','SUPPLY_CHAIN.MENU.SALES_VISIT','拜访计划',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.TABS.SALES.FIELDWORK','SUPPLY_CHAIN.MENU.SALES_VISIT','团队外勤',20,'supply.tabs.sales.fieldwork',NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.SALES_DASHBOARD','SUPPLY_CHAIN.TABS.SALES.FIELDWORK','工作日',10,NULL,NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_LOCATION','SUPPLY_CHAIN.TABS.SALES.FIELDWORK','轨迹对比',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.SALES_VISITS_RECORDS','SUPPLY_CHAIN.MENU.SALES_VISIT','拜访记录',30,'supply.sales.visits.records','/supply-chain/sales/visits/records','sales:dashboard:read','SUPPLY_CHAIN.PAGE.SALES_DASHBOARD'),
 (2,'SUPPLY_CHAIN.PAGE.SALES_VISIT_REVIEW','SUPPLY_CHAIN.MENU.SALES_VISIT','拜访申诉',40,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.SALES_POLICY_VISIT','SUPPLY_CHAIN.MENU.SALES_VISIT','拜访规则',50,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.SALES.PERFORMANCE','SUPPLY_CHAIN.MENU.SALES','绩效薪酬',30,'supply.section.sales.performance',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.SALES_PERFORMANCE','SUPPLY_CHAIN.SECTION.SALES.PERFORMANCE','绩效达成',10,'supply.sales.performance','/supply-chain/sales/performance','analytics:dashboard:read','SUPPLY_CHAIN.PAGE.BI_SALES'),
 (2,'SUPPLY_CHAIN.PAGE.SALES_PAY_STATEMENTS','SUPPLY_CHAIN.SECTION.SALES.PERFORMANCE','薪酬核对',20,'supply.sales.pay-statements','/supply-chain/sales/pay-statements','hr:payroll:self',NULL),
 (1,'SUPPLY_CHAIN.SECTION.ORDER.ORDERS','SUPPLY_CHAIN.MENU.ORDER','接单',10,'supply.section.order.orders',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ORDER_SALES_ORDERS','SUPPLY_CHAIN.SECTION.ORDER.ORDERS','订单列表',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ORDER_SALES_LINES','SUPPLY_CHAIN.SECTION.ORDER.ORDERS','订单商品明细',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.ORDER.DISPATCH','SUPPLY_CHAIN.MENU.ORDER','出库与发货',20,'supply.section.order.dispatch',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.ACTION.ORDER.OUTBOUND.READ','SUPPLY_CHAIN.SECTION.ORDER.DISPATCH','订单出库',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ORDER_SALES_SHIPMENTS','SUPPLY_CHAIN.SECTION.ORDER.DISPATCH','发货单',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.ORDER.SETTLEMENT','SUPPLY_CHAIN.MENU.ORDER','收款与开票',30,'supply.section.order.settlement',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ORDER_SALES_PAYMENTS','SUPPLY_CHAIN.SECTION.ORDER.SETTLEMENT','订单回款',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ORDER_FUND_DOCUMENTS','SUPPLY_CHAIN.SECTION.ORDER.SETTLEMENT','客户资金流水',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ORDER_INVOICES','SUPPLY_CHAIN.SECTION.ORDER.SETTLEMENT','发票管理',30,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.ORDER.REFUNDS','SUPPLY_CHAIN.MENU.ORDER','退款',40,'supply.section.order.refunds',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ORDER_SALES_REFUNDS','SUPPLY_CHAIN.SECTION.ORDER.REFUNDS','退款记录',10,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.CRM.OPERATIONS','SUPPLY_CHAIN.MENU.CRM','门店经营',10,'supply.section.crm.operations',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.CRM_STORE_MINE','SUPPLY_CHAIN.SECTION.CRM.OPERATIONS','我的客户',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.CRM_STORE_PUBLIC','SUPPLY_CHAIN.SECTION.CRM.OPERATIONS','公海',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.CRM_STORE_ANALYSIS','SUPPLY_CHAIN.SECTION.CRM.OPERATIONS','门店覆盖',30,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.CRM.STORES','SUPPLY_CHAIN.MENU.CRM','门店资料',20,'supply.section.crm.stores',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.TABS.CRM.STORES','SUPPLY_CHAIN.SECTION.CRM.STORES','门店名录',10,'supply.tabs.crm.stores',NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.CRM_STORE_LIBRARY','SUPPLY_CHAIN.TABS.CRM.STORES','全部门店',10,NULL,NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.CRM_STORE_COOPERATED','SUPPLY_CHAIN.TABS.CRM.STORES','历史合作',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.CRM_STORE_LEADS','SUPPLY_CHAIN.SECTION.CRM.STORES','门店提报',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.CRM.ACCOUNTS','SUPPLY_CHAIN.MENU.CRM','订货资料',30,'supply.section.crm.accounts',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.TABS.CRM.CUSTOMERS','SUPPLY_CHAIN.SECTION.CRM.ACCOUNTS','订货客户',10,'supply.tabs.crm.customers',NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_PROFILES','SUPPLY_CHAIN.TABS.CRM.CUSTOMERS','客户档案',10,NULL,NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_SHIPPING_ADDRESSES','SUPPLY_CHAIN.TABS.CRM.CUSTOMERS','收货地址',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_LEVELS_TAGS','SUPPLY_CHAIN.SECTION.CRM.ACCOUNTS','客户类型',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_AREAS','SUPPLY_CHAIN.SECTION.CRM.ACCOUNTS','业务地区',30,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.MENU.ERP_MASTER_DATA','SUPPLY_CHAIN.MENU.ERP','商品资料',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_PRODUCTS','SUPPLY_CHAIN.MENU.ERP_MASTER_DATA','商品档案',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.TABS.ERP.ATTRIBUTES','SUPPLY_CHAIN.MENU.ERP_MASTER_DATA','商品属性',20,'supply.tabs.erp.attributes',NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_CATEGORIES','SUPPLY_CHAIN.TABS.ERP.ATTRIBUTES','分类',10,NULL,NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_BRANDS','SUPPLY_CHAIN.TABS.ERP.ATTRIBUTES','品牌',20,NULL,NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_TAGS','SUPPLY_CHAIN.TABS.ERP.ATTRIBUTES','标签',30,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_PRODUCT_SPECIFICATIONS','SUPPLY_CHAIN.MENU.ERP_MASTER_DATA','商品规格',30,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_PRODUCT_PRICES','SUPPLY_CHAIN.MENU.ERP_MASTER_DATA','客户等级价',40,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.MENU.ERP_PROCUREMENT','SUPPLY_CHAIN.MENU.ERP','供应与采购',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_SUPPLIERS_PROFILES','SUPPLY_CHAIN.MENU.ERP_PROCUREMENT','供应商档案',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_ORDERS','SUPPLY_CHAIN.MENU.ERP_PROCUREMENT','采购订单',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.MENU.ERP_INVENTORY','SUPPLY_CHAIN.MENU.ERP','仓储作业',30,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_BALANCES','SUPPLY_CHAIN.MENU.ERP_INVENTORY','库存余额',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_INBOUND','SUPPLY_CHAIN.MENU.ERP_INVENTORY','入库单',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_OUTBOUND','SUPPLY_CHAIN.MENU.ERP_INVENTORY','出库单',30,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_TRANSFERS','SUPPLY_CHAIN.MENU.ERP_INVENTORY','库存调拨',40,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_WAREHOUSES','SUPPLY_CHAIN.MENU.ERP_INVENTORY','仓库资料',50,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.BI.BUSINESS','SUPPLY_CHAIN.MENU.BI','经营概况',10,'supply.section.bi.business',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_OVERVIEW','SUPPLY_CHAIN.SECTION.BI.BUSINESS','经营总览',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_CITY_OPERATING','SUPPLY_CHAIN.SECTION.BI.BUSINESS','城市经营',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_GROSS_PROFIT','SUPPLY_CHAIN.SECTION.BI.BUSINESS','毛利估算',30,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_CITY_COST','SUPPLY_CHAIN.SECTION.BI.BUSINESS','成本分析',40,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.BI.SALES','SUPPLY_CHAIN.MENU.BI','销售与客户',20,'supply.section.bi.sales',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_SALES','SUPPLY_CHAIN.SECTION.BI.SALES','销售业绩',10,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.BI.FINANCE','SUPPLY_CHAIN.MENU.BI','资金分析',30,'supply.section.bi.finance',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_RECEIVABLES','SUPPLY_CHAIN.SECTION.BI.FINANCE','回款与应收',10,'supply.bi.receivables','/supply-chain/bi/receivables','analytics:dashboard:read','SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK'),
 (2,'SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK','SUPPLY_CHAIN.SECTION.BI.FINANCE','回款风险',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_CUSTOMER','SUPPLY_CHAIN.SECTION.BI.SALES','客户交易分析',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_SALES_VISITS','SUPPLY_CHAIN.SECTION.BI.SALES','拜访分析',30,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.BI.PRODUCTS','SUPPLY_CHAIN.MENU.BI','商品与供应',40,'supply.section.bi.products',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_PRODUCT_SALES','SUPPLY_CHAIN.SECTION.BI.PRODUCTS','商品动销',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.TABS.BI.SUPPLY','SUPPLY_CHAIN.SECTION.BI.PRODUCTS','采购与库存',20,'supply.tabs.bi.supply',NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.BI_PRODUCT_INVENTORY','SUPPLY_CHAIN.TABS.BI.SUPPLY','供需概览',10,NULL,NULL,NULL,NULL),
 (3,'SUPPLY_CHAIN.PAGE.BI_INVENTORY_RISK','SUPPLY_CHAIN.TABS.BI.SUPPLY','库存预警',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.BI.PEOPLE','SUPPLY_CHAIN.MENU.BI','组织人事',50,'supply.section.bi.people',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.BI_HR','SUPPLY_CHAIN.SECTION.BI.PEOPLE','人事分析',10,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.HR.ORGANIZATION','SUPPLY_CHAIN.MENU.HR','组织与人员',10,'supply.section.hr.organization',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS','SUPPLY_CHAIN.SECTION.HR.ORGANIZATION','部门管理',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.HR_EMPLOYEE','SUPPLY_CHAIN.SECTION.HR.ORGANIZATION','员工档案',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.HR_POSITION','SUPPLY_CHAIN.SECTION.HR.ORGANIZATION','岗位管理',30,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.HR_CITY_MANAGERS','SUPPLY_CHAIN.SECTION.HR.ORGANIZATION','城市负责人管理',40,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.HR.ATTENDANCE','SUPPLY_CHAIN.MENU.HR','考勤与目标',20,'supply.section.hr.attendance',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.HR_CALENDAR_POLICIES','SUPPLY_CHAIN.SECTION.HR.ATTENDANCE','考勤规则设置',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.HR_ATTENDANCE_APPEALS','SUPPLY_CHAIN.SECTION.HR.ATTENDANCE','员工考勤确认',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS','SUPPLY_CHAIN.SECTION.HR.ATTENDANCE','月度经营目标',30,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL-SETTINGS.MENU','SUPPLY_CHAIN.MENU.HR','薪酬规则',30,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.COMMISSION-RULES','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL-SETTINGS.MENU','商品提成规则',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PERFORMANCE-RULES','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL-SETTINGS.MENU','绩效考核规则',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.ALLOWANCE-RULES','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL-SETTINGS.MENU','员工补贴规则',30,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.HR.PAYROLL','SUPPLY_CHAIN.MENU.HR','薪酬与费用',40,'supply.section.hr.payroll',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.HR_SALES_PAYROLL','SUPPLY_CHAIN.SECTION.HR.PAYROLL','销售薪酬看板',10,'supply.hr.sales-payroll','/supply-chain/hr/sales-payroll','hr:payroll:read','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL'),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL','SUPPLY_CHAIN.SECTION.HR.PAYROLL','月度薪酬核算',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.EXPENSES','SUPPLY_CHAIN.SECTION.HR.PAYROLL','员工报销台账',30,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.COST.PURCHASE','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MENU','采购与损耗',10,'supply.section.cost.purchase',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.PURCHASES','SUPPLY_CHAIN.SECTION.COST.PURCHASE','采购成本记录',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOSSES','SUPPLY_CHAIN.SECTION.COST.PURCHASE','商品损耗记录',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.COST.LOGISTICS','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MENU','仓储与运输',20,'supply.section.cost.logistics',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOGISTICS','SUPPLY_CHAIN.SECTION.COST.LOGISTICS','物流运输记录',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS','SUPPLY_CHAIN.SECTION.COST.LOGISTICS','仓储费用',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.COST.EXPENSES','SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MENU','经营费用',30,'supply.section.cost.expenses',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.OFFICE-RENTALS','SUPPLY_CHAIN.SECTION.COST.EXPENSES','办公室租赁费用',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MARKETING','SUPPLY_CHAIN.SECTION.COST.EXPENSES','营销推广费用',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.DINGHUOBAO.CONNECTIONS','SUPPLY_CHAIN.MENU.DINGHUOBAO','业务接入',10,'supply.section.dinghuobao.connections',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.DINGHUOBAO_OVERVIEW','SUPPLY_CHAIN.SECTION.DINGHUOBAO.CONNECTIONS','订货宝同步中心',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.HUOLALA','SUPPLY_CHAIN.SECTION.DINGHUOBAO.CONNECTIONS','货拉拉接入',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.DINGHUOBAO.JOBS','SUPPLY_CHAIN.MENU.DINGHUOBAO','任务调度',20,'supply.section.dinghuobao.jobs',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.SYNC_SCHEDULE','SUPPLY_CHAIN.SECTION.DINGHUOBAO.JOBS','定时任务',10,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.SETTINGS.ACCESS','SUPPLY_CHAIN.MENU.SETTINGS','账号与授权',10,'supply.section.settings.access',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.SETTINGS.USERS','SUPPLY_CHAIN.SECTION.SETTINGS.ACCESS','用户管理',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.SETTINGS.ROLES','SUPPLY_CHAIN.SECTION.SETTINGS.ACCESS','角色管理',20,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.STORE_TERRITORIES','SUPPLY_CHAIN.SECTION.SETTINGS.ACCESS','客户地域权限',30,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.SETTINGS.PLATFORM','SUPPLY_CHAIN.MENU.SETTINGS','平台配置',20,'supply.section.settings.platform',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.SETTINGS.MENUS','SUPPLY_CHAIN.SECTION.SETTINGS.PLATFORM','菜单管理',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.SETTINGS.PARAMETERS','SUPPLY_CHAIN.SECTION.SETTINGS.PLATFORM','业务参数',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.MENU.PUBLIC_REFERENCE','SUPPLY_CHAIN.MENU.SETTINGS','基础资料',30,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.ADMIN_DIVISIONS','SUPPLY_CHAIN.MENU.PUBLIC_REFERENCE','行政区划',10,NULL,NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.PAGE.SETTINGS_NUMBERING_DICTIONARIES','SUPPLY_CHAIN.MENU.PUBLIC_REFERENCE','数据字典',20,NULL,NULL,NULL,NULL),
 (1,'SUPPLY_CHAIN.SECTION.SETTINGS.AUDIT','SUPPLY_CHAIN.MENU.SETTINGS','审计',40,'supply.section.settings.audit',NULL,NULL,NULL),
 (2,'SUPPLY_CHAIN.SETTINGS.AUDITS','SUPPLY_CHAIN.SECTION.SETTINGS.AUDIT','操作日志',10,NULL,NULL,NULL,NULL);
START TRANSACTION;
-- 节点移出停用目录时，保留原来的有效停用状态。管理员仍可通过菜单管理显式启用。
CREATE TEMPORARY TABLE scm_old_node(tenant_id BINARY(16), id BINARY(16), blocked BOOLEAN, custom_label BOOLEAN, PRIMARY KEY(tenant_id,id));
INSERT INTO scm_old_node
WITH RECURSIVE branches(tenant_id,id,blocked,depth) AS (
 SELECT tenant_id,id,status<>'ACTIVE',0 FROM iam_app_menu_node WHERE application_id=@app AND parent_id IS NULL AND deleted_at IS NULL
 UNION ALL
 SELECT n.tenant_id,n.id,b.blocked OR n.status<>'ACTIVE',b.depth+1 FROM branches b JOIN iam_app_menu_node n
 ON n.application_id=@app AND n.tenant_id=b.tenant_id AND n.parent_id=b.id AND n.deleted_at IS NULL WHERE b.depth<64
)
SELECT b.tenant_id,b.id,b.blocked, BINARY n.display_name<>BINARY r.display_name FROM branches b
JOIN iam_app_menu_node n ON n.application_id=@app AND n.tenant_id=b.tenant_id AND n.id=b.id
JOIN iam_resource r ON r.id=n.resource_id;


-- 第 1 层，使用已经存在的父资源及租户节点。
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT UNHEX(MD5(CONCAT('scm-menu:',m.code))),@app,parent.id,m.code,IF(m.path IS NULL,'MENU','PAGE'),m.permission,m.label,m.ordinal,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scm_navigation_plan m JOIN iam_resource parent ON parent.application_id=@app AND BINARY parent.resource_code=BINARY m.parent_code
LEFT JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
WHERE m.depth=1 AND m.route_key IS NOT NULL AND r.id IS NULL;
UPDATE iam_resource r JOIN scm_navigation_plan m ON BINARY m.code=BINARY r.resource_code
JOIN iam_resource parent ON parent.application_id=@app AND BINARY parent.resource_code=BINARY m.parent_code
SET r.parent_id=parent.id,r.display_name=m.label,r.sort_order=m.ordinal,r.version=r.version+1,r.updated_at=UTC_TIMESTAMP(6)
WHERE m.depth=1 AND r.application_id=@app AND r.deleted_at IS NULL AND
 (NOT(r.parent_id<=>parent.id) OR BINARY r.display_name<>BINARY m.label OR r.sort_order<>m.ordinal);
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,visible,keep_alive,created_at,updated_at)
SELECT r.id,m.route_key,m.path,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scm_navigation_plan m JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
LEFT JOIN iam_resource_ui u ON u.resource_id=r.id WHERE m.depth=1 AND m.route_key IS NOT NULL AND u.resource_id IS NULL;
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT pr.package_version_id,r.id,UTC_TIMESTAMP(6) FROM scm_navigation_plan m
JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
JOIN iam_resource src ON src.application_id=@app AND BINARY src.resource_code=BINARY COALESCE(m.source_code,m.parent_code)
JOIN iam_package_resource pr ON pr.resource_id=src.id WHERE m.depth=1 AND m.route_key IS NOT NULL;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status)
SELECT pn.tenant_id,@app,r.id,pn.id,r.id,r.resource_type,m.label,m.ordinal,TRUE,'ACTIVE' FROM scm_navigation_plan m
JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
JOIN iam_app_menu_node pn ON pn.application_id=@app AND pn.resource_id=r.parent_id AND pn.deleted_at IS NULL
WHERE m.depth=1 AND m.route_key IS NOT NULL AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.application_id=@app AND n.tenant_id=pn.tenant_id AND n.resource_id=r.id);
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id AND r.application_id=n.application_id
JOIN scm_navigation_plan m ON BINARY m.code=BINARY r.resource_code
JOIN iam_app_menu_node pn ON pn.tenant_id=n.tenant_id AND pn.application_id=@app AND pn.resource_id=r.parent_id AND pn.deleted_at IS NULL
LEFT JOIN scm_old_node old ON old.tenant_id=n.tenant_id AND old.id=n.id
SET n.status=IF(old.blocked AND NOT(n.parent_id<=>pn.id),'DISABLED',n.status),n.parent_id=pn.id,
 n.display_name=IF(old.custom_label,n.display_name,m.label),n.sort_order=m.ordinal,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE m.depth=1 AND n.application_id=@app AND n.deleted_at IS NULL AND
 (NOT(n.parent_id<=>pn.id) OR BINARY n.display_name<>BINARY m.label OR n.sort_order<>m.ordinal);

-- 第 2 层，使用已经存在的父资源及租户节点。
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT UNHEX(MD5(CONCAT('scm-menu:',m.code))),@app,parent.id,m.code,IF(m.path IS NULL,'MENU','PAGE'),m.permission,m.label,m.ordinal,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scm_navigation_plan m JOIN iam_resource parent ON parent.application_id=@app AND BINARY parent.resource_code=BINARY m.parent_code
LEFT JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
WHERE m.depth=2 AND m.route_key IS NOT NULL AND r.id IS NULL;
UPDATE iam_resource r JOIN scm_navigation_plan m ON BINARY m.code=BINARY r.resource_code
JOIN iam_resource parent ON parent.application_id=@app AND BINARY parent.resource_code=BINARY m.parent_code
SET r.parent_id=parent.id,r.display_name=m.label,r.sort_order=m.ordinal,r.version=r.version+1,r.updated_at=UTC_TIMESTAMP(6)
WHERE m.depth=2 AND r.application_id=@app AND r.deleted_at IS NULL AND
 (NOT(r.parent_id<=>parent.id) OR BINARY r.display_name<>BINARY m.label OR r.sort_order<>m.ordinal);
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,visible,keep_alive,created_at,updated_at)
SELECT r.id,m.route_key,m.path,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scm_navigation_plan m JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
LEFT JOIN iam_resource_ui u ON u.resource_id=r.id WHERE m.depth=2 AND m.route_key IS NOT NULL AND u.resource_id IS NULL;
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT pr.package_version_id,r.id,UTC_TIMESTAMP(6) FROM scm_navigation_plan m
JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
JOIN iam_resource src ON src.application_id=@app AND BINARY src.resource_code=BINARY COALESCE(m.source_code,m.parent_code)
JOIN iam_package_resource pr ON pr.resource_id=src.id WHERE m.depth=2 AND m.route_key IS NOT NULL;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status)
SELECT pn.tenant_id,@app,r.id,pn.id,r.id,r.resource_type,m.label,m.ordinal,TRUE,'ACTIVE' FROM scm_navigation_plan m
JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
JOIN iam_app_menu_node pn ON pn.application_id=@app AND pn.resource_id=r.parent_id AND pn.deleted_at IS NULL
WHERE m.depth=2 AND m.route_key IS NOT NULL AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.application_id=@app AND n.tenant_id=pn.tenant_id AND n.resource_id=r.id);
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id AND r.application_id=n.application_id
JOIN scm_navigation_plan m ON BINARY m.code=BINARY r.resource_code
JOIN iam_app_menu_node pn ON pn.tenant_id=n.tenant_id AND pn.application_id=@app AND pn.resource_id=r.parent_id AND pn.deleted_at IS NULL
LEFT JOIN scm_old_node old ON old.tenant_id=n.tenant_id AND old.id=n.id
SET n.status=IF(old.blocked AND NOT(n.parent_id<=>pn.id),'DISABLED',n.status),n.parent_id=pn.id,
 n.display_name=IF(old.custom_label,n.display_name,m.label),n.sort_order=m.ordinal,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE m.depth=2 AND n.application_id=@app AND n.deleted_at IS NULL AND
 (NOT(n.parent_id<=>pn.id) OR BINARY n.display_name<>BINARY m.label OR n.sort_order<>m.ordinal);

-- 第 3 层，使用已经存在的父资源及租户节点。
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT UNHEX(MD5(CONCAT('scm-menu:',m.code))),@app,parent.id,m.code,IF(m.path IS NULL,'MENU','PAGE'),m.permission,m.label,m.ordinal,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scm_navigation_plan m JOIN iam_resource parent ON parent.application_id=@app AND BINARY parent.resource_code=BINARY m.parent_code
LEFT JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
WHERE m.depth=3 AND m.route_key IS NOT NULL AND r.id IS NULL;
UPDATE iam_resource r JOIN scm_navigation_plan m ON BINARY m.code=BINARY r.resource_code
JOIN iam_resource parent ON parent.application_id=@app AND BINARY parent.resource_code=BINARY m.parent_code
SET r.parent_id=parent.id,r.display_name=m.label,r.sort_order=m.ordinal,r.version=r.version+1,r.updated_at=UTC_TIMESTAMP(6)
WHERE m.depth=3 AND r.application_id=@app AND r.deleted_at IS NULL AND
 (NOT(r.parent_id<=>parent.id) OR BINARY r.display_name<>BINARY m.label OR r.sort_order<>m.ordinal);
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,visible,keep_alive,created_at,updated_at)
SELECT r.id,m.route_key,m.path,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scm_navigation_plan m JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
LEFT JOIN iam_resource_ui u ON u.resource_id=r.id WHERE m.depth=3 AND m.route_key IS NOT NULL AND u.resource_id IS NULL;
INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT pr.package_version_id,r.id,UTC_TIMESTAMP(6) FROM scm_navigation_plan m
JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
JOIN iam_resource src ON src.application_id=@app AND BINARY src.resource_code=BINARY COALESCE(m.source_code,m.parent_code)
JOIN iam_package_resource pr ON pr.resource_id=src.id WHERE m.depth=3 AND m.route_key IS NOT NULL;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status)
SELECT pn.tenant_id,@app,r.id,pn.id,r.id,r.resource_type,m.label,m.ordinal,TRUE,'ACTIVE' FROM scm_navigation_plan m
JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
JOIN iam_app_menu_node pn ON pn.application_id=@app AND pn.resource_id=r.parent_id AND pn.deleted_at IS NULL
WHERE m.depth=3 AND m.route_key IS NOT NULL AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node n WHERE n.application_id=@app AND n.tenant_id=pn.tenant_id AND n.resource_id=r.id);
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id AND r.application_id=n.application_id
JOIN scm_navigation_plan m ON BINARY m.code=BINARY r.resource_code
JOIN iam_app_menu_node pn ON pn.tenant_id=n.tenant_id AND pn.application_id=@app AND pn.resource_id=r.parent_id AND pn.deleted_at IS NULL
LEFT JOIN scm_old_node old ON old.tenant_id=n.tenant_id AND old.id=n.id
SET n.status=IF(old.blocked AND NOT(n.parent_id<=>pn.id),'DISABLED',n.status),n.parent_id=pn.id,
 n.display_name=IF(old.custom_label,n.display_name,m.label),n.sort_order=m.ordinal,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE m.depth=3 AND n.application_id=@app AND n.deleted_at IS NULL AND
 (NOT(n.parent_id<=>pn.id) OR BINARY n.display_name<>BINARY m.label OR n.sort_order<>m.ordinal);

INSERT IGNORE INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT g.tenant_id,g.application_id,g.role_id,n.id FROM scm_navigation_plan m
JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
JOIN iam_resource src ON src.application_id=@app AND BINARY src.resource_code=BINARY m.source_code
JOIN iam_app_menu_node old ON old.application_id=@app AND old.resource_id=src.id AND old.status='ACTIVE' AND old.deleted_at IS NULL
JOIN iam_app_role_grant g ON g.tenant_id=old.tenant_id AND g.application_id=@app AND g.menu_node_id=old.id
JOIN iam_app_menu_node n ON n.tenant_id=g.tenant_id AND n.application_id=@app AND n.resource_id=r.id AND n.deleted_at IS NULL;
INSERT IGNORE INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT rr.tenant_id,rr.role_id,r.id,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM scm_navigation_plan m
JOIN iam_resource r ON r.application_id=@app AND BINARY r.resource_code=BINARY m.code
JOIN iam_resource src ON src.application_id=@app AND BINARY src.resource_code=BINARY m.source_code
JOIN iam_role_resource rr ON rr.resource_id=src.id AND rr.status='ACTIVE';
CREATE TEMPORARY TABLE scm_empty_dir(id BINARY(16) PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO scm_empty_dir SELECT r.id FROM iam_resource r WHERE r.application_id=@app AND r.resource_type='MENU'
 AND r.resource_code IN ('SUPPLY_CHAIN.MENU.CRM_CUSTOMERS','SUPPLY_CHAIN.MENU.SALES_EXCEPTION','SUPPLY_CHAIN.MENU.SALES_POLICY','SUPPLY_CHAIN.MENU.ERP_SUPPLIERS','SUPPLY_CHAIN.MENU.INTEGRATION_SYNC_CONTROL','SUPPLY_CHAIN.MENU.ERP_MASTER_DATA_ATTRIBUTES','SUPPLY_CHAIN.MENU.SALES_ATTENDANCE')
 AND NOT EXISTS(SELECT 1 FROM iam_resource c WHERE c.parent_id=r.id)
 AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node p JOIN iam_app_menu_node c ON c.tenant_id=p.tenant_id AND c.application_id=p.application_id AND c.parent_id=p.id WHERE p.resource_id=r.id);
DELETE g FROM iam_app_role_grant g JOIN iam_app_menu_node n ON n.tenant_id=g.tenant_id AND n.application_id=g.application_id AND n.id=g.menu_node_id JOIN scm_empty_dir d ON n.resource_id=d.id;
DELETE n FROM iam_app_menu_node n JOIN scm_empty_dir d ON n.resource_id=d.id;
DELETE a FROM iam_role_resource a JOIN scm_empty_dir d ON a.resource_id=d.id;
DELETE c FROM iam_tenant_menu_config c JOIN scm_empty_dir d ON c.resource_id=d.id;
DELETE p FROM iam_package_resource p JOIN scm_empty_dir d ON p.resource_id=d.id;
DELETE u FROM iam_resource_ui u JOIN scm_empty_dir d ON u.resource_id=d.id;
DELETE r FROM iam_resource r JOIN scm_empty_dir d ON r.id=d.id;
UPDATE iam_app_settings SET version=version+1 WHERE application_id=@app;
UPDATE iam_tenant t JOIN iam_app_settings s ON s.tenant_id=t.id AND s.application_id=@app SET t.policy_version=t.policy_version+1;
COMMIT;
DROP TEMPORARY TABLE scm_navigation_plan,scm_empty_dir,scm_old_node;
