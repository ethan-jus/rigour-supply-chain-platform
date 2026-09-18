-- IAM V18：按销售工作台V1.1建立供应链销售管理菜单，并启用飞书销售工作台门户卡片。
-- 仅新增/演进运行时资源，不修改V6/V8等已执行历史迁移。

SET @seed_at = TIMESTAMP('2026-08-06 18:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @sales_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000057');
SET @sales_dashboard = UUID_TO_BIN('019facf2-0000-7000-8000-000000000058');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');

-- 飞书应用仍是外部应用；Portal目标是受控引导页，不复制PC打卡能力。
UPDATE iam_application
   SET app_name = '飞书销售工作台',
       icon_key = 'app-feishu-sales',
       launch_mode = 'FEISHU_DEEPLINK',
       target_uri = '/sales-workbench',
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @app_feishu_sales
   AND app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET display_name = '销售管理', version = version + 1, updated_at = @seed_at
 WHERE id = @sales_menu
   AND resource_code = 'SUPPLY_CHAIN.MENU.SALES';

UPDATE iam_resource
   SET resource_code = 'SUPPLY_CHAIN.PAGE.SALES_DASHBOARD',
       display_name = '销售管控台',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @sales_dashboard
   AND resource_code = 'SUPPLY_CHAIN.PAGE.SALES_INDEX';

UPDATE iam_resource_ui
   SET route_key = 'supply.sales.dashboard',
       route_path = '/supply-chain/sales',
       icon_key = 'Odometer',
       updated_at = @seed_at
 WHERE resource_id = @sales_dashboard;

SET @r121 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000121');
SET @r122 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000122');
SET @r123 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000123');
SET @r124 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000124');
SET @r125 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000125');
SET @r126 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000126');
SET @r127 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000127');
SET @r128 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000128');
SET @r129 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000129');
SET @r130 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000130');
SET @r131 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000131');
SET @r132 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000132');
SET @r133 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000133');
SET @r134 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000134');
SET @r135 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000135');
SET @r136 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000136');
SET @r137 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000137');
SET @r138 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000138');
SET @r139 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000139');
SET @r140 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000140');
SET @r141 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000141');
SET @r142 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000142');
SET @r143 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000143');
SET @r144 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000144');
SET @r145 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000145');
SET @r146 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000146');
SET @r147 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000147');
SET @r148 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000148');
SET @r149 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000149');
SET @r150 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000150');
SET @r151 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000151');
SET @r152 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000152');
SET @r153 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000153');
SET @r154 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000154');
SET @r155 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000155');
SET @r156 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000156');
SET @r157 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000157');
SET @r158 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000158');
SET @r159 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000159');
SET @r160 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000160');
SET @r161 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000161');
SET @r162 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000162');
SET @r163 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000163');
SET @r164 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000164');
SET @r165 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000165');
SET @r166 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000166');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r121,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_ATTENDANCE','MENU',NULL,'外勤考勤',20,'ACTIVE',@seed_at,@seed_at),
    (@r122,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_TODAY','PAGE',NULL,'今日状态',10,'ACTIVE',@seed_at,@seed_at),
    (@r123,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_PUNCHES','PAGE',NULL,'打卡明细',20,'ACTIVE',@seed_at,@seed_at),
    (@r124,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_DAYS','PAGE',NULL,'工作日结',30,'ACTIVE',@seed_at,@seed_at),
    (@r125,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_INTERRUPTION','PAGE',NULL,'定位中断摘要',40,'ACTIVE',@seed_at,@seed_at),
    (@r126,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_ADJUSTMENT','PAGE',NULL,'补卡与异常',50,'ACTIVE',@seed_at,@seed_at),

    (@r127,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_VISIT','MENU',NULL,'拜访管理',30,'ACTIVE',@seed_at,@seed_at),
    (@r128,@app_supply_chain,@r127,'SUPPLY_CHAIN.PAGE.SALES_VISIT_PLAN','PAGE',NULL,'拜访计划',10,'ACTIVE',@seed_at,@seed_at),
    (@r129,@app_supply_chain,@r127,'SUPPLY_CHAIN.PAGE.SALES_VISIT_RECORD','PAGE',NULL,'拜访记录',20,'ACTIVE',@seed_at,@seed_at),
    (@r130,@app_supply_chain,@r127,'SUPPLY_CHAIN.PAGE.SALES_VISIT_REVIEW','PAGE',NULL,'待复核',30,'ACTIVE',@seed_at,@seed_at),
    (@r131,@app_supply_chain,@r127,'SUPPLY_CHAIN.PAGE.SALES_VISIT_APPEAL','PAGE',NULL,'申诉与调整',40,'ACTIVE',@seed_at,@seed_at),

    (@r132,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_STORE','MENU',NULL,'门店覆盖',40,'ACTIVE',@seed_at,@seed_at),
    (@r133,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_ASSIGNED','PAGE',NULL,'负责门店',10,'ACTIVE',@seed_at,@seed_at),
    (@r134,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_UNCOVERED','PAGE',NULL,'未覆盖门店',20,'ACTIVE',@seed_at,@seed_at),
    (@r135,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_VISITED','PAGE',NULL,'已拜访门店',30,'ACTIVE',@seed_at,@seed_at),
    (@r136,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_EFFECTIVE','PAGE',NULL,'有效拜访门店',40,'ACTIVE',@seed_at,@seed_at),
    (@r137,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_CANDIDATE','PAGE',NULL,'新客户门店线索',50,'ACTIVE',@seed_at,@seed_at),

    (@r138,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_ORGANIZATION','MENU',NULL,'销售组织',50,'ACTIVE',@seed_at,@seed_at),
    (@r139,@app_supply_chain,@r138,'SUPPLY_CHAIN.PAGE.SALES_PROFILE','PAGE',NULL,'销售画像',10,'ACTIVE',@seed_at,@seed_at),
    (@r140,@app_supply_chain,@r138,'SUPPLY_CHAIN.PAGE.SALES_TEAM','PAGE',NULL,'销售团队',20,'ACTIVE',@seed_at,@seed_at),
    (@r141,@app_supply_chain,@r138,'SUPPLY_CHAIN.PAGE.SALES_ORG_SCOPE','PAGE',NULL,'任职与城市范围',30,'ACTIVE',@seed_at,@seed_at),

    (@r142,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_TASK','MENU',NULL,'任务与目标',60,'ACTIVE',@seed_at,@seed_at),
    (@r143,@app_supply_chain,@r142,'SUPPLY_CHAIN.PAGE.SALES_VISIT_TASK','PAGE',NULL,'拜访任务',10,'ACTIVE',@seed_at,@seed_at),
    (@r144,@app_supply_chain,@r142,'SUPPLY_CHAIN.PAGE.SALES_TARGET','PAGE',NULL,'目标分配',20,'ACTIVE',@seed_at,@seed_at),
    (@r145,@app_supply_chain,@r142,'SUPPLY_CHAIN.PAGE.SALES_TARGET_EXEMPTION','PAGE',NULL,'目标减免',30,'ACTIVE',@seed_at,@seed_at),

    (@r146,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_EXCEPTION','MENU',NULL,'异常与复核',70,'ACTIVE',@seed_at,@seed_at),
    (@r147,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_PUNCH','PAGE',NULL,'打卡异常',10,'ACTIVE',@seed_at,@seed_at),
    (@r148,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_LOCATION','PAGE',NULL,'定位异常',20,'ACTIVE',@seed_at,@seed_at),
    (@r149,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_EVIDENCE','PAGE',NULL,'拜访证据异常',30,'ACTIVE',@seed_at,@seed_at),
    (@r150,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_RECORDING','PAGE',NULL,'录音与AI异常',40,'ACTIVE',@seed_at,@seed_at),
    (@r151,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_REVIEW','PAGE',NULL,'主管复核',50,'ACTIVE',@seed_at,@seed_at),

    (@r152,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_POLICY','MENU',NULL,'规则配置',80,'ACTIVE',@seed_at,@seed_at),
    (@r153,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_FIELD','PAGE',NULL,'外勤规则',10,'ACTIVE',@seed_at,@seed_at),
    (@r154,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_VISIT','PAGE',NULL,'拜访规则',20,'ACTIVE',@seed_at,@seed_at),
    (@r155,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_RECORDING_AI','PAGE',NULL,'录音与AI规则',30,'ACTIVE',@seed_at,@seed_at),
    (@r156,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_SCOPE','PAGE',NULL,'适用范围',40,'ACTIVE',@seed_at,@seed_at),
    (@r157,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_RELEASE','PAGE',NULL,'发布与历史版本',50,'ACTIVE',@seed_at,@seed_at),

    (@r158,@app_supply_chain,@sales_dashboard,'SUPPLY_CHAIN.API.SALES_DASHBOARD_READ','API','sales:dashboard:read','查询销售管控台',10,'ACTIVE',@seed_at,@seed_at),
    (@r159,@app_supply_chain,@r122,'SUPPLY_CHAIN.API.SALES_WORK_DAY_READ','API','sales:work-day:read','查询销售工作日',10,'ACTIVE',@seed_at,@seed_at),
    (@r160,@app_supply_chain,@r129,'SUPPLY_CHAIN.API.SALES_VISIT_READ','API','sales:visit:read','查询销售拜访',10,'ACTIVE',@seed_at,@seed_at),
    (@r161,@app_supply_chain,@r130,'SUPPLY_CHAIN.API.SALES_VISIT_REVIEW','API','sales:visit:review','复核销售拜访',10,'ACTIVE',@seed_at,@seed_at),
    (@r162,@app_supply_chain,@r148,'SUPPLY_CHAIN.API.SALES_LOCATION_READ','API','sales:location:sensitive:read','查看精确定位',10,'ACTIVE',@seed_at,@seed_at),
    (@r163,@app_supply_chain,@r150,'SUPPLY_CHAIN.API.SALES_RECORDING_PLAY','API','sales:recording:sensitive:play','播放拜访录音',10,'ACTIVE',@seed_at,@seed_at),
    (@r164,@app_supply_chain,@r153,'SUPPLY_CHAIN.API.SALES_POLICY_READ','API','sales:policy:read','查询销售规则',10,'ACTIVE',@seed_at,@seed_at),
    (@r165,@app_supply_chain,@r153,'SUPPLY_CHAIN.API.SALES_POLICY_WRITE','API','sales:policy:write','维护销售规则草稿',20,'ACTIVE',@seed_at,@seed_at),
    (@r166,@app_supply_chain,@r157,'SUPPLY_CHAIN.API.SALES_POLICY_PUBLISH','API','sales:policy:publish','发布销售规则',10,'ACTIVE',@seed_at,@seed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@r121,'supply.sales.attendance.menu',NULL,'Clock',1,0,@seed_at,@seed_at),
    (@r122,'supply.sales.attendance.today','/supply-chain/sales/attendance/today',NULL,1,0,@seed_at,@seed_at),
    (@r123,'supply.sales.attendance.punches','/supply-chain/sales/attendance/punches',NULL,1,0,@seed_at,@seed_at),
    (@r124,'supply.sales.attendance.days','/supply-chain/sales/attendance/days',NULL,1,0,@seed_at,@seed_at),
    (@r125,'supply.sales.attendance.interruptions','/supply-chain/sales/attendance/interruptions',NULL,1,0,@seed_at,@seed_at),
    (@r126,'supply.sales.attendance.adjustments','/supply-chain/sales/attendance/adjustments',NULL,1,0,@seed_at,@seed_at),
    (@r127,'supply.sales.visits.menu',NULL,'Location',1,0,@seed_at,@seed_at),
    (@r128,'supply.sales.visits.plans','/supply-chain/sales/visits/plans',NULL,1,0,@seed_at,@seed_at),
    (@r129,'supply.sales.visits.records','/supply-chain/sales/visits/records',NULL,1,0,@seed_at,@seed_at),
    (@r130,'supply.sales.visits.reviews','/supply-chain/sales/visits/reviews',NULL,1,0,@seed_at,@seed_at),
    (@r131,'supply.sales.visits.appeals','/supply-chain/sales/visits/appeals',NULL,1,0,@seed_at,@seed_at),
    (@r132,'supply.sales.stores.menu',NULL,'Shop',1,0,@seed_at,@seed_at),
    (@r133,'supply.sales.stores.assigned','/supply-chain/sales/stores/assigned',NULL,1,0,@seed_at,@seed_at),
    (@r134,'supply.sales.stores.uncovered','/supply-chain/sales/stores/uncovered',NULL,1,0,@seed_at,@seed_at),
    (@r135,'supply.sales.stores.visited','/supply-chain/sales/stores/visited',NULL,1,0,@seed_at,@seed_at),
    (@r136,'supply.sales.stores.effective','/supply-chain/sales/stores/effective',NULL,1,0,@seed_at,@seed_at),
    (@r137,'supply.sales.stores.candidates','/supply-chain/sales/stores/candidates',NULL,1,0,@seed_at,@seed_at),
    (@r138,'supply.sales.organization.menu',NULL,'User',1,0,@seed_at,@seed_at),
    (@r139,'supply.sales.organization.profiles','/supply-chain/sales/organization/profiles',NULL,1,0,@seed_at,@seed_at),
    (@r140,'supply.sales.organization.teams','/supply-chain/sales/organization/teams',NULL,1,0,@seed_at,@seed_at),
    (@r141,'supply.sales.organization.scopes','/supply-chain/sales/organization/scopes',NULL,1,0,@seed_at,@seed_at),
    (@r142,'supply.sales.tasks.menu',NULL,'Flag',1,0,@seed_at,@seed_at),
    (@r143,'supply.sales.tasks.visits','/supply-chain/sales/tasks/visits',NULL,1,0,@seed_at,@seed_at),
    (@r144,'supply.sales.tasks.targets','/supply-chain/sales/tasks/targets',NULL,1,0,@seed_at,@seed_at),
    (@r145,'supply.sales.tasks.exemptions','/supply-chain/sales/tasks/exemptions',NULL,1,0,@seed_at,@seed_at),
    (@r146,'supply.sales.exceptions.menu',NULL,'Warning',1,0,@seed_at,@seed_at),
    (@r147,'supply.sales.exceptions.punch','/supply-chain/sales/exceptions/punch',NULL,1,0,@seed_at,@seed_at),
    (@r148,'supply.sales.exceptions.location','/supply-chain/sales/exceptions/location',NULL,1,0,@seed_at,@seed_at),
    (@r149,'supply.sales.exceptions.evidence','/supply-chain/sales/exceptions/evidence',NULL,1,0,@seed_at,@seed_at),
    (@r150,'supply.sales.exceptions.recording','/supply-chain/sales/exceptions/recording',NULL,1,0,@seed_at,@seed_at),
    (@r151,'supply.sales.exceptions.reviews','/supply-chain/sales/exceptions/reviews',NULL,1,0,@seed_at,@seed_at),
    (@r152,'supply.sales.policies.menu',NULL,'Setting',1,0,@seed_at,@seed_at),
    (@r153,'supply.sales.policies.field','/supply-chain/sales/policies/field',NULL,1,0,@seed_at,@seed_at),
    (@r154,'supply.sales.policies.visit','/supply-chain/sales/policies/visit',NULL,1,0,@seed_at,@seed_at),
    (@r155,'supply.sales.policies.recording-ai','/supply-chain/sales/policies/recording-ai',NULL,1,0,@seed_at,@seed_at),
    (@r156,'supply.sales.policies.scopes','/supply-chain/sales/policies/scopes',NULL,1,0,@seed_at,@seed_at),
    (@r157,'supply.sales.policies.releases','/supply-chain/sales/policies/releases',NULL,1,0,@seed_at,@seed_at);

-- 新资源进入现行标准套餐；历史套餐版本不改字段，只补充资源关系。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @r121 resource_id UNION ALL SELECT @r122 UNION ALL SELECT @r123 UNION ALL SELECT @r124
    UNION ALL SELECT @r125 UNION ALL SELECT @r126 UNION ALL SELECT @r127 UNION ALL SELECT @r128
    UNION ALL SELECT @r129 UNION ALL SELECT @r130 UNION ALL SELECT @r131 UNION ALL SELECT @r132
    UNION ALL SELECT @r133 UNION ALL SELECT @r134 UNION ALL SELECT @r135 UNION ALL SELECT @r136
    UNION ALL SELECT @r137 UNION ALL SELECT @r138 UNION ALL SELECT @r139 UNION ALL SELECT @r140
    UNION ALL SELECT @r141 UNION ALL SELECT @r142 UNION ALL SELECT @r143 UNION ALL SELECT @r144
    UNION ALL SELECT @r145 UNION ALL SELECT @r146 UNION ALL SELECT @r147 UNION ALL SELECT @r148
    UNION ALL SELECT @r149 UNION ALL SELECT @r150 UNION ALL SELECT @r151 UNION ALL SELECT @r152
    UNION ALL SELECT @r153 UNION ALL SELECT @r154 UNION ALL SELECT @r155 UNION ALL SELECT @r156
    UNION ALL SELECT @r157 UNION ALL SELECT @r158 UNION ALL SELECT @r159 UNION ALL SELECT @r160
    UNION ALL SELECT @r161 UNION ALL SELECT @r162 UNION ALL SELECT @r163 UNION ALL SELECT @r164
    UNION ALL SELECT @r165 UNION ALL SELECT @r166
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- 已存在租户超级管理员需要显式补授权；新租户由Bootstrap按套餐自动获得。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resource_list.resource_id, 'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
  JOIN (
    SELECT @feishu_sales_root resource_id UNION ALL
    SELECT @r121 UNION ALL SELECT @r122 UNION ALL SELECT @r123 UNION ALL SELECT @r124
    UNION ALL SELECT @r125 UNION ALL SELECT @r126 UNION ALL SELECT @r127 UNION ALL SELECT @r128
    UNION ALL SELECT @r129 UNION ALL SELECT @r130 UNION ALL SELECT @r131 UNION ALL SELECT @r132
    UNION ALL SELECT @r133 UNION ALL SELECT @r134 UNION ALL SELECT @r135 UNION ALL SELECT @r136
    UNION ALL SELECT @r137 UNION ALL SELECT @r138 UNION ALL SELECT @r139 UNION ALL SELECT @r140
    UNION ALL SELECT @r141 UNION ALL SELECT @r142 UNION ALL SELECT @r143 UNION ALL SELECT @r144
    UNION ALL SELECT @r145 UNION ALL SELECT @r146 UNION ALL SELECT @r147 UNION ALL SELECT @r148
    UNION ALL SELECT @r149 UNION ALL SELECT @r150 UNION ALL SELECT @r151 UNION ALL SELECT @r152
    UNION ALL SELECT @r153 UNION ALL SELECT @r154 UNION ALL SELECT @r155 UNION ALL SELECT @r156
    UNION ALL SELECT @r157 UNION ALL SELECT @r158 UNION ALL SELECT @r159 UNION ALL SELECT @r160
    UNION ALL SELECT @r161 UNION ALL SELECT @r162 UNION ALL SELECT @r163 UNION ALL SELECT @r164
    UNION ALL SELECT @r165 UNION ALL SELECT @r166
  ) resource_list
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;
