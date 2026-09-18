-- IAM V51：业务导航只展示已有真实接口和页面实现的供应链能力。
-- 页面资源继续保留，待领域接口完成后由后续迁移显式恢复；采购付款单是已确认的业务占位，继续可见。

SET @changed_at = TIMESTAMP('2026-08-17 17:30:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');

-- 尚无领域页面实现的一级模块不进入业务导航。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN (
       'supply.city.menu',
       'supply.hr.menu',
       'supply.channel.menu',
       'supply.bi.menu',
       'supply.erp.index'
   );

-- ERP 页面采用真实接口白名单；采购付款单是本次明确要求保留的只读占位。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_record.resource_type = 'PAGE'
   AND ui_record.route_key LIKE 'supply.erp.%'
   AND ui_record.route_key NOT IN (
       'supply.erp.master-data.products',
       'supply.erp.master-data.skus',
       'supply.erp.master-data.attributes.categories',
       'supply.erp.master-data.attributes.brands',
       'supply.erp.master-data.attributes.specifications',
       'supply.erp.master-data.attributes.tags',
       'supply.erp.suppliers.profiles',
       'supply.erp.procurement.orders',
       'supply.erp.procurement.receipts',
       'supply.erp.procurement.returns',
       'supply.erp.procurement.payments',
       'supply.erp.inventory.inventory',
       'supply.erp.inventory.inbound',
       'supply.erp.inventory.warehouses'
   );

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key = 'supply.erp.cost-settlement.menu';

-- CRM 仅开放已有客户、地址、类型、地区和外部员工查询契约的页面。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_record.resource_type = 'PAGE'
   AND ui_record.route_key LIKE 'supply.crm.%'
   AND ui_record.route_key NOT IN (
       'supply.crm.index',
       'supply.crm.customers.profiles',
       'supply.crm.customers.shipping-addresses',
       'supply.crm.customers.levels-tags',
       'supply.crm.customers.areas',
       'supply.crm.assignments.external-staff'
   );

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key = 'supply.crm.credit-policy.menu';

-- 订单中心仅开放已经落到本地订单、履约、退货和收付款查询接口的页面。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_record.resource_type = 'PAGE'
   AND ui_record.route_key LIKE 'supply.order.%'
   AND ui_record.route_key NOT IN (
       'supply.order.all',
       'supply.order.pending',
       'supply.order.exceptions',
       'supply.order.order-list',
       'supply.order.stock-up',
       'supply.order.shipments',
       'supply.order.returns',
       'supply.order.settlement.receipts',
       'supply.order.settlement.payments'
   );

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key = 'supply.order.stats';

-- 销售管理当前只开放真实实现的管控台、拜访计划、待复核、定位对比和主管复核。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN (
       'supply.sales.attendance.menu',
       'supply.sales.attendance.today',
       'supply.sales.attendance.punches',
       'supply.sales.attendance.days',
       'supply.sales.attendance.interruptions',
       'supply.sales.attendance.adjustments',
       'supply.sales.visits.records',
       'supply.sales.visits.appeals',
       'supply.sales.stores.menu',
       'supply.sales.stores.assigned',
       'supply.sales.stores.uncovered',
       'supply.sales.stores.visited',
       'supply.sales.stores.effective',
       'supply.sales.stores.candidates',
       'supply.sales.organization.menu',
       'supply.sales.organization.profiles',
       'supply.sales.organization.teams',
       'supply.sales.organization.scopes',
       'supply.sales.tasks.menu',
       'supply.sales.tasks.visits',
       'supply.sales.tasks.targets',
       'supply.sales.tasks.exemptions',
       'supply.sales.exceptions.punch',
       'supply.sales.exceptions.evidence',
       'supply.sales.exceptions.recording',
       'supply.sales.policies.menu',
       'supply.sales.policies.field',
       'supply.sales.policies.visit',
       'supply.sales.policies.recording-ai',
       'supply.sales.policies.scopes',
       'supply.sales.policies.releases'
   );

-- 业务设置仅保留已有真实服务端契约的业务字典。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN (
       'supply.setting.index',
       'supply.settings.product-inventory',
       'supply.settings.procurement',
       'supply.settings.customer-levels-tags',
       'supply.settings.credit-policy-templates',
       'supply.settings.order-after-sales',
       'supply.settings.fulfillment-allocation',
       'supply.settings.city-service-scope'
   );

-- 集成区仅展示已有连接、任务、映射、镜像和日志接口的页面。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN (
       'supply.integration.sync-batches',
       'supply.integration.external-id-mappings',
       'supply.integration.reconciliation',
       'supply.integration.sovereignty'
   );

-- 同步收口已有租户的菜单偏好，避免后续管理操作把平台已隐藏的占位能力重新开启。
UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource resource_record ON resource_record.id = menu_config.resource_id
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET menu_config.visible = 0,
       menu_config.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.visible = 0;

-- 名称必须忠实于当前接口：现有页面查询订单镜像和同步日志，不具备 Raw/死信重试能力。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.display_name = CASE ui_record.route_key
           WHEN 'supply.integration.raw-data' THEN '订单镜像'
           WHEN 'supply.integration.retries' THEN '同步日志'
           ELSE resource_record.display_name
       END,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at,
       ui_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN ('supply.integration.raw-data', 'supply.integration.retries');

-- 平台可见性变化需要推进租户策略版本，避免已有导航缓存继续暴露占位能力。
UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;
